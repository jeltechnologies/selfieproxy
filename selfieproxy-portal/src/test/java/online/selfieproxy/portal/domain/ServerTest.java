package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerTest {

	private Server appWithSubdomain(String subdomain) {
		return new Server(subdomain, "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTP, 8080, false), null, null, null);
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
