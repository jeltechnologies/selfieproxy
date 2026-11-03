package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ServerTest {

	private Server appWithSubdomain(String subdomain) {
		return new Server(subdomain, "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTP, 8080, WebAuthMethod.NONE, null, null, null, null, null), null, null, null);
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

	@Test
	void homelabAddressListsEveryEnabledProtocolsPortWithoutDuplicates() {
		Server server = new Server("blog", "example.com", "lab1", "192.168.1.22",
				new WebConfig(Protocol.HTTPS, 443, WebAuthMethod.NONE, null, null, null, null, null),
				new TerminalConfig("ssh.example.com", 22, null, null, null),
				new RemoteDesktopConfig("rdp.example.com", RemoteDesktopProtocol.RDP, 3389, null, null, null, true),
				List.of(new PortForwardingConfig("pf.example.com", PortForwardingProtocol.TCP, 2222, 22, null),
						new PortForwardingConfig("pf2.example.com", PortForwardingProtocol.TCP, 25565, 25565, null)));

		assertEquals("192.168.1.22:443,22,3389,25565", server.homelabAddress());
	}

	@Test
	void homelabAddressWithOneProtocolShowsOnePort() {
		Server server = new Server("blog", "example.com", "lab1", "localserver.home.com",
				new WebConfig(Protocol.HTTP, 80, WebAuthMethod.NONE, null, null, null, null, null), null, null, null);

		assertEquals("localserver.home.com:80", server.homelabAddress());
	}

	@Test
	void homelabAddressWithNoProtocolsIsJustTheHost() {
		Server server = new Server("blog", "example.com", "lab1", "192.168.1.22", null, null, null, null);

		assertEquals("192.168.1.22", server.homelabAddress());
	}
}
