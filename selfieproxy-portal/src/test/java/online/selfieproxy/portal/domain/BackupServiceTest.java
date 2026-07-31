package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.BoringProxyException;
import online.selfieproxy.portal.boringproxy.dto.AgentStatusDto;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.BackupProperties;
import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.SitesWebserverProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class BackupServiceTest {

	@Mock
	private BoringProxyClient boringProxyClient;
	@Mock
	private ServerStore serverStore;
	@Mock
	private LocalWebsiteStore localWebsiteStore;
	@Mock
	private StaticSiteProvisioner staticSiteProvisioner;

	@TempDir
	Path tempDir;

	private final BoringProxyProperties boringProxyProperties =
			new BoringProxyProperties("example.com", "proxylistener", "selfieproxy", "auth", "console");
	private final ThisServerAgentProperties thisServerAgentProperties =
			new ThisServerAgentProperties("selfieproxy-internal-agent", "/dev/null");
	private final SitesWebserverProperties sitesWebserverProperties =
			new SitesWebserverProperties("127.0.0.1", 8090, "/sites-conf", "/sites");

	private BackupService newService() {
		BackupProperties backupProperties = new BackupProperties(tempDir.resolve("staging").toString());
		TunnelMapper tunnelMapper = new TunnelMapper();
		HiddenTunnelFqdnAssigner fqdnAssigner = new HiddenTunnelFqdnAssigner(boringProxyClient);
		ThemeStore themeStore = new ThemeStore(tempDir.resolve("theme.json").toString());
		TerminalSettingsStore terminalSettingsStore =
				new TerminalSettingsStore(tempDir.resolve("remote-console-settings.json").toString());
		return new BackupService(boringProxyClient, tunnelMapper, boringProxyProperties, serverStore,
				fqdnAssigner, localWebsiteStore, staticSiteProvisioner, sitesWebserverProperties,
				thisServerAgentProperties, themeStore, terminalSettingsStore, backupProperties);
	}

	@Test
	void writeBackupThenStageRestoreRoundTripsManifest() throws IOException {
		when(boringProxyClient.listAgents()).thenReturn(Map.of("lab1", new AgentStatusDto(null)));
		Server server = new Server("blog", "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTP, 8080, false), null, null, null);
		when(serverStore.values()).thenReturn(List.of(server));
		when(localWebsiteStore.list()).thenReturn(List.of(new LocalWebsite("blogsite", "example.com", null, false)));

		BackupService service = newService();
		ByteArrayOutputStream backupBytes = new ByteArrayOutputStream();
		RestoreSelection selection = new RestoreSelection(List.of("lab1"), List.of("blog.example.com"), List.of("blogsite.example.com"), Map.of());
		service.writeBackup(backupBytes, ZoneOffset.UTC, selection);

		String stagingId = service.stageRestore(new ByteArrayInputStream(backupBytes.toByteArray()));
		BackupManifest manifest = service.readStagedManifest(stagingId);

		assertEquals(List.of("lab1"), manifest.homelabs());
		assertEquals(1, manifest.servers().size());
		assertEquals("blog", manifest.servers().get(0).subdomain());
		assertEquals(1, manifest.localWebsites().size());
		assertEquals("blogsite.example.com", manifest.localWebsites().get(0).fqdn());
	}

	@Test
	void writeBackupOnlyIncludesSelectedItems() throws IOException {
		when(boringProxyClient.listAgents()).thenReturn(Map.of("lab1", new AgentStatusDto(null), "lab2", new AgentStatusDto(null)));
		Server blogServer = new Server("blog", "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTP, 8080, false), null, null, null);
		Server shopServer = new Server("shop", "example.com", "lab2", "127.0.0.1",
				new WebConfig(Protocol.HTTP, 8081, false), null, null, null);
		when(serverStore.values()).thenReturn(List.of(blogServer, shopServer));
		when(localWebsiteStore.list()).thenReturn(List.of(new LocalWebsite("blogsite", "example.com", null, false), new LocalWebsite("shopsite", "example.com", null, false)));

		BackupService service = newService();
		ByteArrayOutputStream backupBytes = new ByteArrayOutputStream();
		RestoreSelection selection = new RestoreSelection(List.of("lab1"), List.of("blog.example.com"), List.of("blogsite.example.com"), Map.of());
		service.writeBackup(backupBytes, ZoneOffset.UTC, selection);

		String stagingId = service.stageRestore(new ByteArrayInputStream(backupBytes.toByteArray()));
		BackupManifest manifest = service.readStagedManifest(stagingId);

		assertEquals(List.of("lab1"), manifest.homelabs());
		assertEquals(1, manifest.servers().size());
		assertEquals("blog", manifest.servers().get(0).subdomain());
		assertEquals(1, manifest.localWebsites().size());
		assertEquals("blogsite.example.com", manifest.localWebsites().get(0).fqdn());
	}

	/**
	 * The new multi-protocol Application shape: one Application with all 4 protocols enabled must
	 * round-trip through export -> stage -> read with its nested per-protocol configs intact, and
	 * buildManifest must strip both credential slots (Terminal/RemoteDesktop) along the way, exactly
	 * like the old single-protocol withoutSecret() did for its one credential slot.
	 */
	@Test
	void writeBackupThenStageRestoreRoundTripsMultiProtocolServer() throws IOException {
		when(boringProxyClient.listAgents()).thenReturn(Map.of("lab1", new AgentStatusDto(null)));
		Server server = new Server("proxmox", "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTPS, 443, true),
				new TerminalConfig("proxmox-example-com-22.example.com", 22, 51000, "root", "cipher-ssh"),
				new RemoteDesktopConfig("proxmox-example-com-3389.example.com", RemoteDesktopProtocol.RDP, 3389,
						51001, "admin", "cipher-rdp", true),
				List.of(new PortForwardingConfig("proxmox-example-com-1234.example.com", PortForwardingProtocol.TCP, 1234, 8080)));
		when(serverStore.values()).thenReturn(List.of(server));
		when(localWebsiteStore.list()).thenReturn(List.of());

		BackupService service = newService();
		ByteArrayOutputStream backupBytes = new ByteArrayOutputStream();
		RestoreSelection selection = new RestoreSelection(List.of("lab1"), List.of("proxmox.example.com"), List.of(), Map.of());
		service.writeBackup(backupBytes, ZoneOffset.UTC, selection);

		String stagingId = service.stageRestore(new ByteArrayInputStream(backupBytes.toByteArray()));
		BackupManifest manifest = service.readStagedManifest(stagingId);

		assertEquals(1, manifest.servers().size());
		Server exported = manifest.servers().get(0);
		assertEquals("proxmox", exported.subdomain());
		assertEquals(Protocol.HTTPS, exported.web().protocol());
		assertEquals("proxmox-example-com-22.example.com", exported.terminal().fqdn());
		assertEquals(22, exported.terminal().port());
		assertNull(exported.terminal().encryptedSecret(), "an export must never carry a live credential");
		assertEquals(RemoteDesktopProtocol.RDP, exported.remoteDesktop().protocol());
		assertNull(exported.remoteDesktop().encryptedSecret(), "an export must never carry a live credential");
		assertEquals(1234, exported.portForwarding().get(0).publicPort());
		assertEquals(8080, exported.portForwarding().get(0).targetPort());
	}

	@Test
	void stageRestoreRejectsZipSlipEntries() throws IOException {
		BackupManifest manifest = new BackupManifest(BackupManifest.CURRENT_VERSION, Instant.now().toString(),
				"example.com", List.of(), List.of(), List.of(), "light", new TerminalSettings(15, "dark", "default"));
		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
			zip.putNextEntry(new ZipEntry("manifest.json"));
			zip.write(JsonMapper.builder().build().writeValueAsBytes(manifest));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("../evil.txt"));
			zip.write("hi".getBytes());
			zip.closeEntry();
		}

		BackupService service = newService();
		assertThrows(IOException.class,
				() -> service.stageRestore(new ByteArrayInputStream(zipBytes.toByteArray())));

		Path stagingRoot = tempDir.resolve("staging");
		try (var listing = Files.list(stagingRoot)) {
			assertTrue(listing.findAny().isEmpty(), "a rejected upload must leave no staging directory behind");
		}
	}

	@Test
	void applyRestoreCreatesHomelabWithFreshSecretAndRecreatesServerTunnel() throws IOException {
		Server server = new Server("blog", "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTP, 8080, false), null, null, null);
		BackupManifest manifest = new BackupManifest(BackupManifest.CURRENT_VERSION, Instant.now().toString(),
				"example.com", List.of("lab1"), List.of(server), List.of(), "light", new TerminalSettings(15, "dark", "default"));
		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
			zip.putNextEntry(new ZipEntry("manifest.json"));
			zip.write(JsonMapper.builder().build().writeValueAsBytes(manifest));
			zip.closeEntry();
		}

		when(boringProxyClient.listAgents()).thenReturn(Map.of());
		doThrow(new BoringProxyException(404, "Tunnel doesn't exist")).when(boringProxyClient)
				.deleteTunnel(eq("blog.example.com"));

		BackupService service = newService();
		String stagingId = service.stageRestore(new ByteArrayInputStream(zipBytes.toByteArray()));

		RestoreResult result = service.applyRestore(stagingId,
				new RestoreSelection(List.of("lab1"), List.of("blog.example.com"), List.of(), Map.of()));

		assertEquals(1, result.homelabsRestored());
		assertEquals(1, result.serversRestored());
		assertEquals(0, result.localWebsitesRestored());
		assertTrue(result.failures().isEmpty(), "unexpected failures: " + result.failures());

		verify(boringProxyClient, times(1)).createAgent("admin", "lab1");
		verify(boringProxyClient, times(1)).createToken("admin", "lab1");
		verify(boringProxyClient).createTunnel(any());
		verify(serverStore).save(server);
	}

	@Test
	void applyRestoreAcceptsApexServerWithBlankSubdomain() throws IOException {
		Server server = new Server(null, "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTP, 8080, false), null, null, null);
		BackupManifest manifest = new BackupManifest(BackupManifest.CURRENT_VERSION, Instant.now().toString(),
				"example.com", List.of("lab1"), List.of(server), List.of(), "light", new TerminalSettings(15, "dark", "default"));
		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
			zip.putNextEntry(new ZipEntry("manifest.json"));
			zip.write(JsonMapper.builder().build().writeValueAsBytes(manifest));
			zip.closeEntry();
		}

		when(boringProxyClient.listAgents()).thenReturn(Map.of());
		doThrow(new BoringProxyException(404, "Tunnel doesn't exist")).when(boringProxyClient)
				.deleteTunnel(eq("example.com"));

		BackupService service = newService();
		String stagingId = service.stageRestore(new ByteArrayInputStream(zipBytes.toByteArray()));

		RestoreResult result = service.applyRestore(stagingId,
				new RestoreSelection(List.of("lab1"), List.of("example.com"), List.of(), Map.of()));

		assertEquals(1, result.serversRestored());
		assertTrue(result.failures().isEmpty(), "unexpected failures: " + result.failures());
		verify(serverStore).save(server);
	}

	/**
	 * A multi-protocol Application must create exactly one tunnel per enabled protocol on restore
	 * (not one, not four regardless of what's actually enabled), and the saved record must carry
	 * back each remote-access protocol's boringproxy-assigned exposedPort captured from that
	 * specific createTunnel response.
	 */
	@Test
	void applyRestoreCreatesOneTunnelPerEnabledProtocol() throws IOException {
		Server server = new Server("proxmox", "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTPS, 443, false),
				new TerminalConfig("proxmox-example-com-22.example.com", 22, null, "root", null),
				null,
				List.of(new PortForwardingConfig("proxmox-example-com-1234.example.com", PortForwardingProtocol.TCP, 1234, 8080)));
		BackupManifest manifest = new BackupManifest(BackupManifest.CURRENT_VERSION, Instant.now().toString(),
				"example.com", List.of(), List.of(server), List.of(), "light", new TerminalSettings(15, "dark", "default"));
		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
			zip.putNextEntry(new ZipEntry("manifest.json"));
			zip.write(JsonMapper.builder().build().writeValueAsBytes(manifest));
			zip.closeEntry();
		}

		when(boringProxyClient.listAgents()).thenReturn(Map.of("lab1", new AgentStatusDto(null)));
		when(boringProxyClient.createTunnel(any())).thenAnswer(invocation -> {
			CreateTunnelRequestDto request = invocation.getArgument(0);
			return new TunnelDto(request.domain(), null, 0, null, null, 55555, null, "127.0.0.1",
					request.clientPort(), Boolean.TRUE.equals(request.allowExternalTcp()), request.tlsTermination(),
					false, false, "admin", "lab1", null, null);
		});

		BackupService service = newService();
		String stagingId = service.stageRestore(new ByteArrayInputStream(zipBytes.toByteArray()));

		RestoreResult result = service.applyRestore(stagingId,
				new RestoreSelection(List.of(), List.of("proxmox.example.com"), List.of(), Map.of()));

		assertEquals(1, result.serversRestored());
		assertTrue(result.failures().isEmpty(), "unexpected failures: " + result.failures());
		verify(boringProxyClient, times(3)).createTunnel(any());

		ArgumentCaptor<Server> savedCaptor = ArgumentCaptor.forClass(Server.class);
		verify(serverStore).save(savedCaptor.capture());
		Server saved = savedCaptor.getValue();
		assertEquals(55555, saved.terminal().exposedPort());
		assertNull(saved.remoteDesktop());
		assertEquals(1234, saved.portForwarding().get(0).publicPort());
	}

	@Test
	void applyRestoreSkipsContentRestoreForRedirectModeLocalWebsite() throws IOException {
		LocalWebsite site = new LocalWebsite("old", "example.com", "https://www.example.com", false);
		BackupManifest manifest = new BackupManifest(BackupManifest.CURRENT_VERSION, Instant.now().toString(),
				"example.com", List.of(), List.of(), List.of(site), "light", new TerminalSettings(15, "dark", "default"));
		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
			zip.putNextEntry(new ZipEntry("manifest.json"));
			zip.write(JsonMapper.builder().build().writeValueAsBytes(manifest));
			zip.closeEntry();
		}

		doThrow(new BoringProxyException(404, "Tunnel doesn't exist")).when(boringProxyClient)
				.deleteTunnel(eq("old.example.com"));

		BackupService service = newService();
		String stagingId = service.stageRestore(new ByteArrayInputStream(zipBytes.toByteArray()));

		RestoreResult result = service.applyRestore(stagingId,
				new RestoreSelection(List.of(), List.of(), List.of("old.example.com"), Map.of()));

		assertEquals(1, result.localWebsitesRestored());
		assertTrue(result.failures().isEmpty(), "unexpected failures: " + result.failures());
		verify(staticSiteProvisioner).provision("old.example.com", "https://www.example.com");
		verify(staticSiteProvisioner, never()).replaceContents(any(), any());
		verify(localWebsiteStore).save(site);
	}

	@Test
	void diffManifestFlagsExistingItemsAgainstLiveState() {
		Server existingServer = new Server("blog", "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTP, 8080, false), null, null, null);
		Server newServer = new Server("shop", "example.com", "lab2", "127.0.0.1",
				new WebConfig(Protocol.HTTP, 8081, false), null, null, null);
		BackupManifest manifest = new BackupManifest(BackupManifest.CURRENT_VERSION, Instant.now().toString(),
				"example.com", List.of("lab1", "lab2"), List.of(existingServer, newServer),
				List.of(new LocalWebsite("blogsite", "example.com", null, false), new LocalWebsite("newsite", "example.com", null, false)),
				"light", new TerminalSettings(15, "dark", "default"));

		when(boringProxyClient.listAgents()).thenReturn(Map.of("lab1", new AgentStatusDto(null)));
		when(serverStore.find("blog.example.com")).thenReturn(existingServer);
		when(serverStore.find("shop.example.com")).thenReturn(null);
		when(localWebsiteStore.find("blogsite.example.com")).thenReturn(new LocalWebsite("blogsite", "example.com", null, false));
		when(localWebsiteStore.find("newsite.example.com")).thenReturn(null);

		BackupService service = newService();
		RestoreDiff diff = service.diffManifest(manifest);

		assertEquals(Set.of("lab1"), diff.existingHomelabs());
		assertEquals(Set.of("blog.example.com"), diff.existingServerFqdns());
		assertEquals(Set.of("blogsite.example.com"), diff.existingLocalWebsiteFqdns());
	}
}
