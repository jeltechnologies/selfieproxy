package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.LocalWebsiteDemoProperties;
import online.selfieproxy.portal.config.SitesWebserverProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;

@ExtendWith(MockitoExtension.class)
class LocalWebsiteDemoBootstrapTest {

	@Mock
	private BoringProxyClient boringProxyClient;

	@Mock
	private LocalWebsiteStore localWebsiteStore;

	@Mock
	private StaticSiteProvisioner staticSiteProvisioner;

	@TempDir
	Path tempDir;

	private static final BoringProxyProperties DOMAIN = new BoringProxyProperties("example.com", "proxylistener",
			"selfieproxy", "auth", "console");
	private static final ThisServerAgentProperties AGENT = new ThisServerAgentProperties("selfieproxy-internal-agent",
			"/data/secret");
	private static final SitesWebserverProperties SITES = new SitesWebserverProperties("127.0.0.1", 8090,
			"/sites-conf", "/sites");

	private LocalWebsiteDemoBootstrap newBootstrap(Path contentMarker, Path redirectMarker) {
		LocalWebsiteDemoProperties properties = new LocalWebsiteDemoProperties("www", contentMarker.toString(),
				redirectMarker.toString());
		return new LocalWebsiteDemoBootstrap(boringProxyClient, localWebsiteStore, staticSiteProvisioner, SITES,
				AGENT, DOMAIN, properties);
	}

	@Test
	void createsBothDefaultSitesWhenNothingExistsYet() {
		Path contentMarker = tempDir.resolve("content-bootstrapped");
		Path redirectMarker = tempDir.resolve("redirect-bootstrapped");
		when(localWebsiteStore.find(any())).thenReturn(null);

		newBootstrap(contentMarker, redirectMarker).createDefaultLocalWebsitesIfMissing();

		verify(boringProxyClient).createTunnel(argThatDomain("www.example.com"));
		verify(staticSiteProvisioner).provision("www.example.com", null);
		verify(staticSiteProvisioner).replaceContents(eq("www.example.com"), any(InputStream.class));
		verify(localWebsiteStore).save(new LocalWebsite("www", "example.com", null, true));

		verify(boringProxyClient).createTunnel(argThatDomain("example.com"));
		verify(staticSiteProvisioner).provision("example.com", "https://www.example.com");
		verify(localWebsiteStore).save(new LocalWebsite("", "example.com", "https://www.example.com", true));

		assertTrue(Files.exists(contentMarker), "content marker should be written after bootstrap");
		assertTrue(Files.exists(redirectMarker), "redirect marker should be written after bootstrap");
	}

	@Test
	void contentMarkerAlreadyPresentSkipsContentStepRegardlessOfRedirectMarker() throws IOException {
		Path contentMarker = tempDir.resolve("content-bootstrapped");
		Path redirectMarker = tempDir.resolve("redirect-bootstrapped");
		Files.writeString(contentMarker, "");
		when(localWebsiteStore.find("example.com")).thenReturn(null);

		newBootstrap(contentMarker, redirectMarker).createDefaultLocalWebsitesIfMissing();

		verify(boringProxyClient, never()).createTunnel(argThatDomain("www.example.com"));
		verify(staticSiteProvisioner, never()).provision(eq("www.example.com"), any());
		verify(boringProxyClient).createTunnel(argThatDomain("example.com"));
		assertTrue(Files.exists(redirectMarker), "redirect marker should still be written independently");
	}

	@Test
	void redirectMarkerAlreadyPresentButContentMarkerAbsentStillCreatesContentSite() throws IOException {
		Path contentMarker = tempDir.resolve("content-bootstrapped");
		Path redirectMarker = tempDir.resolve("redirect-bootstrapped");
		Files.writeString(redirectMarker, "");
		when(localWebsiteStore.find("www.example.com")).thenReturn(null);

		newBootstrap(contentMarker, redirectMarker).createDefaultLocalWebsitesIfMissing();

		verify(boringProxyClient).createTunnel(argThatDomain("www.example.com"));
		verify(boringProxyClient, never()).createTunnel(argThatDomain("example.com"));
		assertTrue(Files.exists(contentMarker), "content marker should be written independently");
	}

	@Test
	void skipsCreationWhenDefaultSitesAlreadyExistButStillWritesMarkers() {
		Path contentMarker = tempDir.resolve("content-bootstrapped");
		Path redirectMarker = tempDir.resolve("redirect-bootstrapped");
		when(localWebsiteStore.find("www.example.com")).thenReturn(new LocalWebsite("www", "example.com", null, true));
		when(localWebsiteStore.find("example.com"))
				.thenReturn(new LocalWebsite("", "example.com", "https://www.example.com", true));

		newBootstrap(contentMarker, redirectMarker).createDefaultLocalWebsitesIfMissing();

		verify(boringProxyClient, never()).createTunnel(any());
		verify(staticSiteProvisioner, never()).provision(any(), any());
		assertTrue(Files.exists(contentMarker), "bootstrap marker should still be written so bootstrap never re-runs");
		assertTrue(Files.exists(redirectMarker), "bootstrap marker should still be written so bootstrap never re-runs");
	}

	private static CreateTunnelRequestDto argThatDomain(String domain) {
		return org.mockito.ArgumentMatchers.argThat(request -> request != null && domain.equals(request.domain()));
	}
}
