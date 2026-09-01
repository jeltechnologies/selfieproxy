package online.selfieproxy.portal.domain;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.SitesWebserverProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;

/**
 * Creates any tunnel ServerStore/LocalWebsiteStore imply but boringproxy doesn't actually have.
 * Add-only and idempotent: it never deletes and never touches a tunnel that already exists, so it
 * can't bounce a live connection -- removing orphans and rebuilding changed settings stays
 * TunnelReconciler's job at startup.
 *
 * <p>It exists because every tunnel mutation in this portal is a non-transactional
 * delete-then-recreate (ServerController.syncTunnels, DomainsController.renameDomain,
 * BackupService.restore, TunnelReconciler itself). If the recreate half fails -- a full disk
 * breaking boringproxy's authorized_keys write, a tunnel-port collision, an FQDN already in use --
 * the record survives in the store while its tunnel is gone, which takes the Server off the
 * internet silently and used to make its own edit page unopenable (ServerController.certPending
 * asked boringproxy for a tunnel that no longer existed). Left alone, that state persisted until
 * someone happened to restart the portal.
 *
 * <p>{@link #sweep()} only creates a tunnel that was already missing in the previous sweep. That
 * two-pass rule is what makes locking unnecessary: an ordinary edit's delete-then-recreate window
 * is a couple of seconds and can never span two sweeps, so the sweep can't race in and resurrect a
 * tunnel an admin is in the middle of replacing. The cost is that a genuine repair lands one to two
 * intervals late, which is invisible next to how long the drift used to last.
 */
@Component
public class TunnelRepairService {

	private static final Logger log = LoggerFactory.getLogger(TunnelRepairService.class);
	private static final String OWNER = "admin";
	private static final long SWEEP_INTERVAL_MS = 60_000;

	private final BoringProxyClient boringProxyClient;
	private final TunnelMapper tunnelMapper;
	private final ServerStore serverStore;
	private final LocalWebsiteStore localWebsiteStore;
	private final ThisServerAgentProperties thisServerAgentProperties;
	private final SitesWebserverProperties sitesWebserverProperties;

	private volatile Set<String> missingLastSweep = Set.of();

	public TunnelRepairService(BoringProxyClient boringProxyClient, TunnelMapper tunnelMapper, ServerStore serverStore,
			LocalWebsiteStore localWebsiteStore, ThisServerAgentProperties thisServerAgentProperties,
			SitesWebserverProperties sitesWebserverProperties) {
		this.boringProxyClient = boringProxyClient;
		this.tunnelMapper = tunnelMapper;
		this.serverStore = serverStore;
		this.localWebsiteStore = localWebsiteStore;
		this.thisServerAgentProperties = thisServerAgentProperties;
		this.sitesWebserverProperties = sitesWebserverProperties;
	}

	@Scheduled(initialDelay = SWEEP_INTERVAL_MS, fixedDelay = SWEEP_INTERVAL_MS)
	public void sweep() {
		Set<String> live;
		try {
			live = boringProxyClient.listTunnels().keySet();
		} catch (RuntimeException e) {
			log.warn("Tunnel repair sweep could not read boringproxy's tunnels: {}", e.getMessage());
			return;
		}

		Set<String> missing = missingDomains(live);
		Set<String> confirmed = new LinkedHashSet<>(missing);
		confirmed.retainAll(missingLastSweep);
		missingLastSweep = missing;

		if (!confirmed.isEmpty()) {
			log.warn("Tunnel repair: {} is in Selfie Proxy's configuration but has no tunnel in boringproxy, recreating", confirmed);
			createMissing(live, confirmed::contains);
		}
	}

	/** Every desired-but-absent tunnel, no two-pass delay -- used right after TunnelReconciler's wipe and whenever a single Server is opened for editing. */
	public int createMissing() {
		return createMissing(boringProxyClient.listTunnels().keySet(), domain -> true);
	}

	/** Same, narrowed to one Server, and never allowed to fail the page that asked for it. */
	public void createMissing(Server server) {
		try {
			createServerTunnels(server, boringProxyClient.listTunnels().keySet(), domain -> true);
		} catch (RuntimeException e) {
			log.warn("Could not repair tunnels for server {}: {}", server.fqdn(), e.getMessage());
		}
	}

	private Set<String> missingDomains(Set<String> live) {
		Set<String> missing = new LinkedHashSet<>();
		for (Server server : serverStore.values()) {
			tunnelMapper.tunnelPlans(server, OWNER).values().forEach(plan -> collectAbsent(missing, live, plan.fqdn()));
			tunnelMapper.portForwardingTunnelPlans(server, OWNER).forEach(plan -> collectAbsent(missing, live, plan.fqdn()));
		}
		for (LocalWebsite website : localWebsiteStore.list()) {
			collectAbsent(missing, live, website.fqdn());
		}
		return missing;
	}

	private void collectAbsent(Set<String> missing, Set<String> live, String fqdn) {
		if (!live.contains(fqdn)) {
			missing.add(fqdn);
		}
	}

	private int createMissing(Set<String> live, Predicate<String> selected) {
		int created = 0;
		for (Server server : serverStore.values()) {
			created += createServerTunnels(server, live, selected);
		}
		for (LocalWebsite website : localWebsiteStore.list()) {
			if (wanted(website.fqdn(), live, selected) && create(localWebsiteRequest(website.fqdn())) != null) {
				created++;
			}
		}
		return created;
	}

	/**
	 * Terminal/Remote Desktop tunnels get a fresh boringproxy-assigned loopback port every time
	 * they're recreated, and selfieproxy-remote-console dials the one recorded in servers.json --
	 * so the record has to be written back here, exactly as ServerController.syncTunnels does on an
	 * ordinary save.
	 */
	private int createServerTunnels(Server server, Set<String> live, Predicate<String> selected) {
		int created = 0;
		Server updated = server;
		boolean portsChanged = false;

		for (Map.Entry<ServerProtocol, TunnelMapper.ProtocolTunnel> entry : tunnelMapper.tunnelPlans(server, OWNER).entrySet()) {
			TunnelMapper.ProtocolTunnel plan = entry.getValue();
			if (!wanted(plan.fqdn(), live, selected)) {
				continue;
			}
			TunnelDto tunnel = create(plan.request());
			if (tunnel == null) {
				continue;
			}
			created++;
			if (entry.getKey() == ServerProtocol.TERMINAL) {
				updated = updated.withTerminalExposedPort(tunnel.tunnelPort());
				portsChanged = true;
			} else if (entry.getKey() == ServerProtocol.REMOTE_DESKTOP) {
				updated = updated.withRemoteDesktopExposedPort(tunnel.tunnelPort());
				portsChanged = true;
			}
		}

		for (TunnelMapper.ProtocolTunnel plan : tunnelMapper.portForwardingTunnelPlans(server, OWNER)) {
			if (wanted(plan.fqdn(), live, selected) && create(plan.request()) != null) {
				created++;
			}
		}

		if (portsChanged) {
			serverStore.save(updated);
		}
		return created;
	}

	private boolean wanted(String fqdn, Set<String> live, Predicate<String> selected) {
		return !live.contains(fqdn) && selected.test(fqdn);
	}

	/** One failed tunnel is logged and skipped -- the next sweep retries it, so a transient cause repairs itself without an admin noticing. */
	private TunnelDto create(CreateTunnelRequestDto request) {
		try {
			TunnelDto tunnel = boringProxyClient.createTunnel(request);
			log.info("Created missing tunnel '{}'", request.domain());
			return tunnel;
		} catch (RuntimeException e) {
			log.warn("Failed to create missing tunnel '{}': {}", request.domain(), e.getMessage());
			return null;
		}
	}

	/** Mirrors LocalWebsiteController.toCreateTunnelRequest -- owner is hardcoded since a repair has no HTTP session behind it. */
	private CreateTunnelRequestDto localWebsiteRequest(String fqdn) {
		return new CreateTunnelRequestDto(
				fqdn,
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
				null,
				null,
				null,
				null,
				null);
	}
}
