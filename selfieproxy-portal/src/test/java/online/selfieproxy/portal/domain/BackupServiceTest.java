package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.BoringProxyException;
import online.selfieproxy.portal.boringproxy.dto.AgentStatusDto;
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
	private ExposedAppStore exposedAppStore;
	@Mock
	private LocalWebsiteStore localWebsiteStore;
	@Mock
	private StaticSiteProvisioner staticSiteProvisioner;

	@TempDir
	Path tempDir;

	private final BoringProxyProperties boringProxyProperties =
			new BoringProxyProperties("example.com", "proxylistener", "selfieproxy", "auth");
	private final TunnelMapper tunnelMapper = new TunnelMapper(boringProxyProperties);
	private final ThisServerAgentProperties thisServerAgentProperties =
			new ThisServerAgentProperties("selfieproxy-internal-agent", "/dev/null");
	private final SitesWebserverProperties sitesWebserverProperties =
			new SitesWebserverProperties("127.0.0.1", 8090, "/sites-conf", "/sites");

	private BackupService newService() {
		BackupProperties backupProperties = new BackupProperties(tempDir.resolve("staging").toString());
		return new BackupService(boringProxyClient, tunnelMapper, boringProxyProperties, exposedAppStore,
				localWebsiteStore, staticSiteProvisioner, sitesWebserverProperties, thisServerAgentProperties,
				backupProperties);
	}

	@Test
	void writeBackupThenStageRestoreRoundTripsManifest() throws IOException {
		when(boringProxyClient.listAgents()).thenReturn(Map.of("lab1", new AgentStatusDto(null)));
		TunnelDto tunnel = new TunnelDto("blog.example.com", null, 0, null, null, 0, null,
				"127.0.0.1", 8080, false, "server", false, false, "admin", "lab1", null, null);
		when(boringProxyClient.listTunnels()).thenReturn(Map.of("blog.example.com", tunnel));
		when(exposedAppStore.reconcile(any())).thenAnswer(inv -> inv.getArgument(0));
		when(localWebsiteStore.list()).thenReturn(List.of(new LocalWebsite("blogsite", false)));

		BackupService service = newService();
		ByteArrayOutputStream backupBytes = new ByteArrayOutputStream();
		RestoreSelection selection = new RestoreSelection(List.of("lab1"), List.of("blog"), List.of("blogsite"));
		service.writeBackup(backupBytes, ZoneOffset.UTC, selection);

		String stagingId = service.stageRestore(new ByteArrayInputStream(backupBytes.toByteArray()));
		BackupManifest manifest = service.readStagedManifest(stagingId);

		assertEquals(List.of("lab1"), manifest.homelabs());
		assertEquals(1, manifest.exposedApps().size());
		assertEquals("blog", manifest.exposedApps().get(0).subdomain());
		assertEquals(1, manifest.localWebsites().size());
		assertEquals("blogsite", manifest.localWebsites().get(0).domain());
	}

	@Test
	void writeBackupOnlyIncludesSelectedItems() throws IOException {
		when(boringProxyClient.listAgents()).thenReturn(Map.of("lab1", new AgentStatusDto(null), "lab2", new AgentStatusDto(null)));
		TunnelDto blogTunnel = new TunnelDto("blog.example.com", null, 0, null, null, 0, null,
				"127.0.0.1", 8080, false, "server", false, false, "admin", "lab1", null, null);
		TunnelDto shopTunnel = new TunnelDto("shop.example.com", null, 0, null, null, 0, null,
				"127.0.0.1", 8081, false, "server", false, false, "admin", "lab2", null, null);
		when(boringProxyClient.listTunnels()).thenReturn(Map.of("blog.example.com", blogTunnel, "shop.example.com", shopTunnel));
		when(exposedAppStore.reconcile(any())).thenAnswer(inv -> inv.getArgument(0));
		when(localWebsiteStore.list()).thenReturn(List.of(new LocalWebsite("blogsite", false), new LocalWebsite("shopsite", false)));

		BackupService service = newService();
		ByteArrayOutputStream backupBytes = new ByteArrayOutputStream();
		RestoreSelection selection = new RestoreSelection(List.of("lab1"), List.of("blog"), List.of("blogsite"));
		service.writeBackup(backupBytes, ZoneOffset.UTC, selection);

		String stagingId = service.stageRestore(new ByteArrayInputStream(backupBytes.toByteArray()));
		BackupManifest manifest = service.readStagedManifest(stagingId);

		assertEquals(List.of("lab1"), manifest.homelabs());
		assertEquals(1, manifest.exposedApps().size());
		assertEquals("blog", manifest.exposedApps().get(0).subdomain());
		assertEquals(1, manifest.localWebsites().size());
		assertEquals("blogsite", manifest.localWebsites().get(0).domain());
	}

	@Test
	void stageRestoreRejectsZipSlipEntries() throws IOException {
		BackupManifest manifest = new BackupManifest(BackupManifest.CURRENT_VERSION, Instant.now().toString(),
				"example.com", List.of(), List.of(), List.of());
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
	void applyRestoreCreatesHomelabWithFreshSecretAndRecreatesExposedAppTunnel() throws IOException {
		ExposedApp app = new ExposedApp("blog", null, "lab1", ExposedAppType.WEB_APPLICATION, Protocol.HTTP,
				"127.0.0.1", 8080, null, null, false);
		BackupManifest manifest = new BackupManifest(BackupManifest.CURRENT_VERSION, Instant.now().toString(),
				"example.com", List.of("lab1"), List.of(app), List.of());
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
				new RestoreSelection(List.of("lab1"), List.of("blog"), List.of()));

		assertEquals(1, result.homelabsRestored());
		assertEquals(1, result.exposedAppsRestored());
		assertEquals(0, result.localWebsitesRestored());
		assertTrue(result.failures().isEmpty(), "unexpected failures: " + result.failures());

		verify(boringProxyClient, times(1)).createAgent("admin", "lab1");
		verify(boringProxyClient, times(1)).createToken("admin", "lab1");
		verify(boringProxyClient).createTunnel(any());
		verify(exposedAppStore).save(app);
	}
}
