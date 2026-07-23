package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LocalWebsiteTest {

	@Test
	void fqdnComposesLabelAndDomain() {
		assertEquals("blog.example.com", new LocalWebsite("blog", "example.com", null, false).fqdn());
	}

	@Test
	void fqdnFallsBackToBareDomainWhenLabelIsNull() {
		assertEquals("example.com", new LocalWebsite(null, "example.com", null, false).fqdn());
	}

	@Test
	void fqdnFallsBackToBareDomainWhenLabelIsBlank() {
		assertEquals("example.com", new LocalWebsite("", "example.com", null, false).fqdn());
	}

	@Test
	void isRedirectFalseInContentMode() {
		assertFalse(new LocalWebsite("blog", "example.com", null, false).isRedirect());
		assertFalse(new LocalWebsite("blog", "example.com", "", false).isRedirect());
	}

	@Test
	void isRedirectTrueWhenRedirectToIsSet() {
		LocalWebsite site = new LocalWebsite(null, "example.com", "https://www.example.com", false);
		assertTrue(site.isRedirect());
		assertEquals("example.com", site.fqdn());
	}
}
