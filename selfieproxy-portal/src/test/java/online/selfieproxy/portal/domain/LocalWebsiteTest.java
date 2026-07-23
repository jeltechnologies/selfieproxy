package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LocalWebsiteTest {

	@Test
	void fqdnComposesLabelAndDomain() {
		assertEquals("blog.example.com", new LocalWebsite("blog", "example.com").fqdn());
	}

	@Test
	void fqdnFallsBackToBareDomainWhenLabelIsNull() {
		assertEquals("example.com", new LocalWebsite(null, "example.com").fqdn());
	}

	@Test
	void fqdnFallsBackToBareDomainWhenLabelIsBlank() {
		assertEquals("example.com", new LocalWebsite("", "example.com").fqdn());
	}
}
