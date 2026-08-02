package online.selfieproxy.portal.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.BoringProxyException;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.BackupProperties;
import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.SitesWebserverProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds and applies whole-server backups covering Homelabs, Servers, and
 * Local Websites (config + actual content files) -- see selfieproxy-portal/CLAUDE.md's "Backup and
 * restore" section for the full product behavior and the deliberate exclusions (agent secrets,
 * identity-provider admin account). A Server's Terminal/RemoteDesktop credentials are
 * excluded too, for the same host-specific-secret reason: they're encrypted with a key that never
 * leaves this server (see NetworkServiceCredentialCipher), so an exported ciphertext would be
 * undecryptable on a different one -- buildManifest strips both credential slots
 * (Server.withoutSecrets) while keeping the rest of the server, including each slot's username, so
 * the admin only has to re-enter the password once per protocol after an import (see
 * ConsoleConnectController) rather than losing the whole config. Reads serverStore.values()
 * directly rather than deriving from live boringproxy tunnels -- see ServerStore's own javadoc
 * on why that store is the sole source of truth for the Application list now.
 */
@Component
public class BackupService {

	private static final Logger log = LoggerFactory.getLogger(BackupService.class);

	private static final String OWNER = "admin";
	private static final String MANIFEST_ENTRY = "manifest.json";
	private static final String LOCAL_WEBSITES_PREFIX = "local-websites/";
	/** boringproxy's own error text for deleting a tunnel that's already gone -- see BoringProxyException, mirrors LocalWebsiteController.deleteTunnelIgnoringMissing. */
	private static final String TUNNEL_MISSING_MESSAGE = "Tunnel doesn't exist";
	/** Same wait ServerController/LocalWebsiteController already use between deleting and recreating a tunnel. */
	private static final long TUNNEL_RECREATE_WAIT_MS = 2000;

	// Same bespoke camelCase mapper convention as ServerStore/LocalWebsiteStore --
	// this is an internal persistence format, not a REST DTO, so it doesn't need the
	// globally-configured snake_case naming strategy.
	private final ObjectMapper objectMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.enable(SerializationFeature.INDENT_OUTPUT)
			.build();

	private final BoringProxyClient boringProxyClient;
	private final TunnelMapper tunnelMapper;
	private final BoringProxyProperties boringProxyProperties;
	private final ServerStore serverStore;
	private final HiddenTunnelFqdnAssigner fqdnAssigner;
	private final LocalWebsiteStore localWebsiteStore;
	private final StaticSiteProvisioner staticSiteProvisioner;
	private final SitesWebserverProperties sitesWebserverProperties;
	private final ThisServerAgentProperties thisServerAgentProperties;
	private final ThemeStore themeStore;
	private final TerminalSettingsStore terminalSettingsStore;
	private final Path stagingRoot;

	public BackupService(BoringProxyClient boringProxyClient, TunnelMapper tunnelMapper,
			BoringProxyProperties boringProxyProperties, ServerStore serverStore,
			HiddenTunnelFqdnAssigner fqdnAssigner, LocalWebsiteStore localWebsiteStore,
			StaticSiteProvisioner staticSiteProvisioner, SitesWebserverProperties sitesWebserverProperties,
			ThisServerAgentProperties thisServerAgentProperties, ThemeStore themeStore,
			TerminalSettingsStore terminalSettingsStore, BackupProperties backupProperties) {
		this.boringProxyClient = boringProxyClient;
		this.tunnelMapper = tunnelMapper;
		this.boringProxyProperties = boringProxyProperties;
		this.serverStore = serverStore;
		this.fqdnAssigner = fqdnAssigner;
		this.localWebsiteStore = localWebsiteStore;
		this.staticSiteProvisioner = staticSiteProvisioner;
		this.sitesWebserverProperties = sitesWebserverProperties;
		this.thisServerAgentProperties = thisServerAgentProperties;
		this.themeStore = themeStore;
		this.terminalSettingsStore = terminalSettingsStore;
		this.stagingRoot = Path.of(backupProperties.restoreStagingPath());
	}

	/**
	 * Builds the manifest from live state, keeps only what selection picked, and streams
	 * manifest.json + every selected Local Website's content into out as one ZIP. zone is the
	 * browser's local timezone (see BackupController), used only for the manifest's informational
	 * createdAt field.
	 */
	public void writeBackup(OutputStream out, ZoneId zone, RestoreSelection selection) throws IOException {
		BackupManifest manifest = filterManifest(buildManifest(zone), selection);
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
			zip.write(objectMapper.writeValueAsBytes(manifest));
			zip.closeEntry();

			for (LocalWebsite site : manifest.localWebsites()) {
				String fqdn = site.fqdn();
				staticSiteProvisioner.writeEntries(fqdn, LOCAL_WEBSITES_PREFIX + fqdn + "/", zip);
			}
		}
	}

	/** Every Homelab except "This Server" -- same filter ServerController.homelabs()/DashboardController already apply. */
	private List<String> homelabNames() {
		return boringProxyClient.listAgents().keySet().stream()
				.filter(name -> !thisServerAgentProperties.agentName().equals(name))
				.sorted()
				.toList();
	}

	/** The full manifest built from live server state -- also used by BackupController to list what's available on the backup selection page (see filterManifest). */
	public BackupManifest buildManifest(ZoneId zone) {
		List<Server> servers = serverStore.values().stream()
				.map(Server::withoutSecrets)
				.sorted(Comparator.comparing(server -> server.subdomain() == null ? "" : server.subdomain()))
				.toList();

		String createdAt = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.MILLIS).toString();
		return new BackupManifest(BackupManifest.CURRENT_VERSION, createdAt,
				boringProxyProperties.primaryDomain(), homelabNames(), servers, localWebsiteStore.list(),
				themeStore.load().id(), terminalSettingsStore.load());
	}

	/** Extracts zipData into a fresh staging directory and validates its manifest, without touching any live state. Returns the staging id for readStagedManifest/applyRestore/cancelStaged. */
	public String stageRestore(InputStream zipData) throws IOException {
		String stagingId = UUID.randomUUID().toString();
		Path dir = stagingRoot.resolve(stagingId);
		Files.createDirectories(dir);
		try {
			ZipUtils.extract(zipData, dir);
			readManifest(dir);
		} catch (IOException | RuntimeException e) {
			deleteStagingQuietly(dir);
			throw e;
		}
		return stagingId;
	}

	/** The manifest of an already-staged restore, for the picker UI. Throws IllegalArgumentException if stagingId is unknown/expired. */
	public BackupManifest readStagedManifest(String stagingId) {
		return readManifest(stagingRoot.resolve(stagingId));
	}

	/**
	 * Which items in manifest already exist on this server, for the restore wizard's
	 * per-item New/Existing status and contextual warnings -- computed against live
	 * state, same lookups doApplyRestore itself relies on (ensureHomelab's existingAgents
	 * set, serverStore/localWebsiteStore as the source of truth for what an ordinary
	 * edit would overwrite).
	 */
	public RestoreDiff diffManifest(BackupManifest manifest) {
		Set<String> existingHomelabs = new HashSet<>(boringProxyClient.listAgents().keySet());
		Set<String> existingServers = manifest.servers().stream()
				.map(Server::fqdn)
				.filter(fqdn -> serverStore.find(fqdn) != null)
				.collect(Collectors.toSet());
		Set<String> existingLocalWebsites = manifest.localWebsites().stream()
				.map(LocalWebsite::fqdn)
				.filter(fqdn -> localWebsiteStore.find(fqdn) != null)
				.collect(Collectors.toSet());
		return new RestoreDiff(existingHomelabs, existingServers, existingLocalWebsites);
	}

	/** Keeps only the homelabs/exposed servers/local websites selection picked -- what the backup page's checkbox tree narrows a backup ZIP down to. */
	public BackupManifest filterManifest(BackupManifest manifest, RestoreSelection selection) {
		Set<String> homelabs = new HashSet<>(selection.homelabs());
		Set<String> serverFqdns = new HashSet<>(selection.serverFqdns());
		Set<String> localWebsiteFqdns = new HashSet<>(selection.localWebsiteFqdns());
		return new BackupManifest(manifest.version(), manifest.createdAt(), manifest.sourcePrimaryDomain(),
				manifest.homelabs().stream().filter(homelabs::contains).toList(),
				manifest.servers().stream().filter(server -> serverFqdns.contains(server.fqdn())).toList(),
				manifest.localWebsites().stream().filter(site -> localWebsiteFqdns.contains(site.fqdn())).toList(),
				manifest.theme(), manifest.terminalSettings());
	}

	/**
	 * Applies selection from a staged restore: creates missing Homelabs (always with a brand-new
	 * secret -- see class docs), then for each selected Exposed App and Local Website deletes any
	 * existing tunnel(s) at that domain and recreates them from the backup's values -- the same
	 * delete-then-recreate-with-wait pattern ServerController/LocalWebsiteController already use
	 * for an ordinary edit, just applied in bulk. Restoring an Application restores all of its
	 * enabled protocols together, atomically -- there's no partial-protocol selection in the wizard,
	 * since an Application is presented everywhere else as one logical unit. A failure on one item
	 * is recorded and does not stop the rest of the restore. The staging directory is always removed
	 * afterward.
	 */
	public RestoreResult applyRestore(String stagingId, RestoreSelection selection) {
		Path stagingDir = stagingRoot.resolve(stagingId);
		BackupManifest manifest = readManifest(stagingDir);
		try {
			return doApplyRestore(manifest, selection, stagingDir);
		} finally {
			deleteStagingQuietly(stagingDir);
		}
	}

	public void cancelStaged(String stagingId) {
		deleteStagingQuietly(stagingRoot.resolve(stagingId));
	}

	private RestoreResult doApplyRestore(BackupManifest manifest, RestoreSelection selection, Path stagingDir) {
		Set<String> existingAgents = new HashSet<>(boringProxyClient.listAgents().keySet());
		List<String> failures = new ArrayList<>();

		// Always applied, unconditionally -- these are single global settings, not itemized/selectable
		// like Homelabs/Apps/Local Websites below, so there's no RestoreSelection entry for either.
		if (manifest.theme() != null) {
			try {
				themeStore.save(Theme.fromId(manifest.theme()));
			} catch (Exception e) {
				failures.add("Appearance setting: " + e.getMessage());
			}
		}
		if (manifest.terminalSettings() != null) {
			try {
				terminalSettingsStore.save(manifest.terminalSettings());
			} catch (Exception e) {
				failures.add("SSH console settings: " + e.getMessage());
			}
		}

		int homelabsRestored = 0;
		for (String name : selection.homelabs()) {
			if (!DnsLabelValidator.isValid(name)) {
				failures.add("Homelab " + name + ": can only contain letters, numbers, and hyphens, and cannot start or end with a hyphen");
				continue;
			}
			try {
				if (ensureHomelab(name, existingAgents)) {
					homelabsRestored++;
				}
			} catch (Exception e) {
				failures.add("Homelab " + name + ": " + e.getMessage());
			}
		}

		Map<String, Server> appsByFqdn = manifest.servers().stream()
				.collect(Collectors.toMap(Server::fqdn, a -> a));
		int serversRestored = 0;
		for (String fqdnKey : selection.serverFqdns()) {
			Server original = appsByFqdn.get(fqdnKey);
			if (original == null) {
				failures.add("Server " + fqdnKey + ": not found in configuration export");
				continue;
			}
			if (original.subdomain() != null && !original.subdomain().isBlank() && !DnsLabelValidator.isValid(original.subdomain())) {
				failures.add("Server " + fqdnKey + ": can only contain letters, numbers, and hyphens, and cannot start or end with a hyphen");
				continue;
			}
			try {
				ensureHomelab(original.homelabName(), existingAgents);
				// Substitute the target domain the restore wizard's per-item <select> chose (default:
				// the ZIP's own domain if it's still registered here, else the primary domain -- see
				// BackupController). Every enabled protocol's hidden tunnel FQDN is derived from the
				// Application's own (new) full FQDN, so all of them are regenerated here too, not just
				// Web's -- see HiddenTunnelFqdnAssigner/rebuildForDomain.
				String targetDomain = selection.domainOverridesByFqdn().getOrDefault(fqdnKey, original.domain());
				Server server = rebuildForDomain(original, targetDomain);
				Map<ServerProtocol, TunnelMapper.ProtocolTunnel> plans = tunnelMapper.tunnelPlans(server, OWNER);
				List<TunnelMapper.ProtocolTunnel> portForwardPlans = tunnelMapper.portForwardingTunnelPlans(server, OWNER);
				plans.values().forEach(plan -> deleteTunnelIgnoringMissing(plan.fqdn()));
				portForwardPlans.forEach(plan -> deleteTunnelIgnoringMissing(plan.fqdn()));
				if (!plans.isEmpty() || !portForwardPlans.isEmpty()) {
					sleep();
				}
				for (Map.Entry<ServerProtocol, TunnelMapper.ProtocolTunnel> entry : plans.entrySet()) {
					TunnelDto tunnel = boringProxyClient.createTunnel(entry.getValue().request());
					if (entry.getKey() == ServerProtocol.TERMINAL) {
						server = server.withTerminalExposedPort(tunnel.tunnelPort());
					} else if (entry.getKey() == ServerProtocol.REMOTE_DESKTOP) {
						server = server.withRemoteDesktopExposedPort(tunnel.tunnelPort());
					}
				}
				for (TunnelMapper.ProtocolTunnel plan : portForwardPlans) {
					boringProxyClient.createTunnel(plan.request());
				}
				serverStore.save(server);
				serversRestored++;
			} catch (Exception e) {
				failures.add("Server " + fqdnKey + ": " + e.getMessage());
			}
		}

		Map<String, LocalWebsite> sitesByFqdn = manifest.localWebsites().stream()
				.collect(Collectors.toMap(LocalWebsite::fqdn, s -> s));
		int localWebsitesRestored = 0;
		for (String fqdnKey : selection.localWebsiteFqdns()) {
			LocalWebsite original = sitesByFqdn.get(fqdnKey);
			if (original == null) {
				failures.add("Local website " + fqdnKey + ": not found in configuration export");
				continue;
			}
			if (original.label() != null && !original.label().isBlank() && !DnsLabelValidator.isValid(original.label())) {
				failures.add("Local website " + fqdnKey + ": can only contain letters, numbers, and hyphens, and cannot start or end with a hyphen");
				continue;
			}
			try {
				// demo is always forced false on import -- it's a single-server, single-bootstrap
				// concept (see LocalWebsite.demo()) that must never follow an export onto another server.
				LocalWebsite site = new LocalWebsite(original.label(),
						selection.domainOverridesByFqdn().getOrDefault(fqdnKey, original.domain()), original.redirectTo(),
						false);
				String fqdn = site.fqdn();
				deleteTunnelIgnoringMissing(fqdn);
				sleep();
				boringProxyClient.createTunnel(toLocalWebsiteTunnelRequest(fqdn));
				staticSiteProvisioner.provision(fqdn, site.redirectTo());
				if (!site.isRedirect()) {
					restoreLocalWebsiteContent(stagingDir, fqdnKey, fqdn);
				}
				localWebsiteStore.save(site);
				localWebsitesRestored++;
			} catch (Exception e) {
				failures.add("Local website " + fqdnKey + ": " + e.getMessage());
			}
		}

		return new RestoreResult(homelabsRestored, serversRestored, localWebsitesRestored, failures);
	}

	/**
	 * Regenerates every hidden tunnel FQDN (Terminal/RemoteDesktop always under the primary domain,
	 * PortForwarding under targetDomain -- the Application's own, possibly just-changed domain)
	 * since each is derived from the Application's own full FQDN. exposedPort is reset to null on
	 * each -- it's recaptured from the recreated tunnel's response right after (see doApplyRestore).
	 * Credentials (already stripped by buildManifest/withoutSecrets) pass through unchanged, landing
	 * the restored Application in the same "no credential yet" state a fresh add would be in.
	 */
	private Server rebuildForDomain(Server original, String targetDomain) {
		String newServerFqdn = original.subdomain() == null || original.subdomain().isBlank()
				? targetDomain : original.subdomain() + "." + targetDomain;
		TerminalConfig terminal = original.terminal() == null ? null : new TerminalConfig(
				fqdnAssigner.assign(newServerFqdn, "terminal", boringProxyProperties.primaryDomain(), Set.of()),
				original.terminal().port(), null, original.terminal().username(), original.terminal().encryptedSecret());
		RemoteDesktopConfig remoteDesktop = original.remoteDesktop() == null ? null : new RemoteDesktopConfig(
				fqdnAssigner.assign(newServerFqdn, "remotedesktop", boringProxyProperties.primaryDomain(), Set.of()),
				original.remoteDesktop().protocol(), original.remoteDesktop().port(), null,
				original.remoteDesktop().username(), original.remoteDesktop().encryptedSecret(),
				original.remoteDesktop().ignoreCertificate());
		List<PortForwardingConfig> portForwarding = original.portForwarding() == null ? null
				: rebuildPortForwarding(original.portForwarding(), newServerFqdn, targetDomain);
		return new Server(original.subdomain(), targetDomain, original.homelabName(), original.host(),
				original.web(), terminal, remoteDesktop, portForwarding);
	}

	/** Each entry gets its own freshly-assigned fqdn under targetDomain; excludeFqdns grows as the loop proceeds so entries in the same rebuilt list can never collide with each other. */
	private List<PortForwardingConfig> rebuildPortForwarding(List<PortForwardingConfig> original, String newServerFqdn, String targetDomain) {
		Set<String> excludeFqdns = new HashSet<>();
		List<PortForwardingConfig> rebuilt = new ArrayList<>();
		for (PortForwardingConfig entry : original) {
			String fqdn = fqdnAssigner.assign(newServerFqdn, String.valueOf(entry.publicPort()), targetDomain, excludeFqdns);
			excludeFqdns.add(fqdn);
			rebuilt.add(new PortForwardingConfig(fqdn, entry.protocol(), entry.publicPort(), entry.targetPort(), entry.description()));
		}
		return rebuilt;
	}

	/** Creates the agent (with a brand-new secret) if it doesn't already exist on this server; returns whether it was created. */
	private boolean ensureHomelab(String name, Set<String> existingAgents) {
		if (existingAgents.contains(name)) {
			return false;
		}
		boringProxyClient.createAgent(OWNER, name);
		boringProxyClient.createToken(OWNER, name);
		existingAgents.add(name);
		return true;
	}

	/** The Tunnel record can already be gone (eg. restoring to a fresh server) -- don't let that block recreating it, mirrors LocalWebsiteController.deleteTunnelIgnoringMissing. */
	private void deleteTunnelIgnoringMissing(String domain) {
		try {
			boringProxyClient.deleteTunnel(domain);
		} catch (BoringProxyException e) {
			if (!TUNNEL_MISSING_MESSAGE.equals(e.getMessage())) {
				throw e;
			}
		}
	}

	private void sleep() {
		try {
			Thread.sleep(TUNNEL_RECREATE_WAIT_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for tunnel teardown", e);
		}
	}

	/** Same shape as LocalWebsiteController's own private toCreateTunnelRequest -- Local Websites always point at the shared selfieproxy-local-websites container through the hidden "This Server" homelab. */
	private CreateTunnelRequestDto toLocalWebsiteTunnelRequest(String domain) {
		return new CreateTunnelRequestDto(
				domain,
				OWNER,
				thisServerAgentProperties.agentName(),
				sitesWebserverProperties.port(),
				sitesWebserverProperties.host(),
				null,
				null,
				null,
				null,
				null,
				"server",
				null,
				null,
				null);
	}

	/** Re-zips the staged local-websites/&lt;fqdn&gt;/ directory in memory and feeds it through StaticSiteProvisioner's existing atomic-swap upload path -- local sites are small enough that the extra round trip isn't worth a Path-based overload. */
	/** sourceFqdn is where the content lives in the staged ZIP (local-websites/&lt;sourceFqdn&gt;/); targetFqdn is where it's restored to -- they differ whenever the restore wizard retargeted the site to a different domain than the ZIP's own. */
	private void restoreLocalWebsiteContent(Path stagingDir, String sourceFqdn, String targetFqdn) throws IOException {
		Path siteDir = stagingDir.resolve("local-websites").resolve(sourceFqdn);
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
			ZipUtils.writeDirectoryEntries(siteDir, "", zip);
		}
		staticSiteProvisioner.replaceContents(targetFqdn, new ByteArrayInputStream(buffer.toByteArray()));
	}

	private BackupManifest readManifest(Path stagingDir) {
		Path manifestFile = stagingDir.resolve(MANIFEST_ENTRY);
		if (!Files.exists(manifestFile)) {
			throw new IllegalArgumentException("Configuration export is missing manifest.json, or the staging id is unknown/expired.");
		}
		BackupManifest manifest;
		try {
			manifest = objectMapper.readValue(manifestFile.toFile(), BackupManifest.class);
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to read configuration export manifest.json", e);
		}
		if (manifest.version() != BackupManifest.CURRENT_VERSION) {
			throw new IllegalArgumentException(
					"Unsupported configuration export version " + manifest.version() + " (expected " + BackupManifest.CURRENT_VERSION + ")");
		}
		return manifest;
	}

	private void deleteStagingQuietly(Path dir) {
		try {
			ZipUtils.deleteRecursively(dir);
		} catch (IOException e) {
			log.warn("Failed to clean up restore staging directory {}", dir, e);
		}
	}
}
