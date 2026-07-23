package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExposedAppTest {

	private ExposedApp appWithSubdomain(String subdomain) {
		return new ExposedApp(subdomain, null, "lab1", ExposedAppType.WEB_APPLICATION, Protocol.HTTP,
				"127.0.0.1", 8080, null, null, false, "example.com", null, null, null, false);
	}

	@Test
	void fqdnComposesSubdomainAndDomain() {
		assertEquals("blog.example.com", appWithSubdomain("blog").fqdn());
	}

	@Test
	void fqdnFallsBackToBareDomainWhenSubdomainIsNull() {
		assertEquals("example.com", appWithSubdomain(null).fqdn());
	}

	@Test
	void fqdnFallsBackToBareDomainWhenSubdomainIsBlank() {
		assertEquals("example.com", appWithSubdomain("").fqdn());
	}
}
