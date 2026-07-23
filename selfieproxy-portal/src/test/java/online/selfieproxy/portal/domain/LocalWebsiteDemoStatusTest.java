package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.LocalWebsiteDemoProperties;

@ExtendWith(MockitoExtension.class)
class LocalWebsiteDemoStatusTest {

	@Mock
	private LocalWebsiteStore localWebsiteStore;

	private static final BoringProxyProperties DOMAIN = new BoringProxyProperties("example.com", "proxylistener",
			"selfieproxy", "auth", "console");
	private static final LocalWebsiteDemoProperties PROPERTIES = new LocalWebsiteDemoProperties("www",
			"/tmp/content-marker", "/tmp/redirect-marker");

	private LocalWebsiteDemoStatus newStatus() {
		return new LocalWebsiteDemoStatus(localWebsiteStore, DOMAIN, PROPERTIES);
	}

	@Test
	void demoFlagSetIsUnmodified() {
		when(localWebsiteStore.find("www.example.com")).thenReturn(new LocalWebsite("www", "example.com", null, true));

		assertTrue(newStatus().isDemoContentUnmodified());
	}

	@Test
	void demoFlagClearedIsNotUnmodified() {
		when(localWebsiteStore.find("www.example.com")).thenReturn(new LocalWebsite("www", "example.com", null, false));

		assertFalse(newStatus().isDemoContentUnmodified());
	}

	@Test
	void missingSiteIsNotUnmodified() {
		when(localWebsiteStore.find("www.example.com")).thenReturn(null);

		assertFalse(newStatus().isDemoContentUnmodified());
	}

	@Test
	void redirectModeSiteIsNotUnmodifiedContentEvenWithDemoFlagSet() {
		when(localWebsiteStore.find("www.example.com"))
				.thenReturn(new LocalWebsite("www", "example.com", "https://elsewhere.example.com", true));

		assertFalse(newStatus().isDemoContentUnmodified());
	}

	@Test
	void unmodifiedRedirectIsDetected() {
		when(localWebsiteStore.find("example.com"))
				.thenReturn(new LocalWebsite("", "example.com", "https://www.example.com", true));

		assertTrue(newStatus().isDemoRedirectUnmodified());
	}

	@Test
	void repointedRedirectIsNotUnmodified() {
		when(localWebsiteStore.find("example.com"))
				.thenReturn(new LocalWebsite("", "example.com", "https://somewhere-else.example.com", true));

		assertFalse(newStatus().isDemoRedirectUnmodified());
	}

	@Test
	void deletedRedirectIsNotUnmodified() {
		when(localWebsiteStore.find("example.com")).thenReturn(null);

		assertFalse(newStatus().isDemoRedirectUnmodified());
	}
}
