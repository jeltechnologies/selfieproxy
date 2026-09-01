package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import online.selfieproxy.portal.security.NetworkServiceCredentialCipher;

/**
 * The authentication exceptions have to arrive at boringproxy on the tunnel itself -- a list that
 * stops at the portal protects nothing and, worse, reads on the edit page as though it were in
 * force. boringproxy keeps them as one newline-separated string rather than a list, so its agents
 * can still compare tunnels by value (see database.go's Tunnel and agent.go's SyncTunnels).
 */
class TunnelMapperTest {

	@TempDir
	private Path tempDir;

	private TunnelMapper mapper() {
		return new TunnelMapper(new NetworkServiceCredentialCipher(tempDir.resolve("key").toString()));
	}

	private Server serverWith(WebAuthMethod method, List<String> exemptPaths) {
		return new Server("music", "example.com", "home", "127.0.0.1",
				new WebConfig(Protocol.HTTPS, 443, method, null, null, null, null, exemptPaths),
				null, null, null);
	}

	@Test
	void exemptPathsReachTheWebTunnelNewlineJoined() {
		TunnelMapper.ProtocolTunnel plan = mapper()
				.tunnelPlans(serverWith(WebAuthMethod.SSO, List.of("/login*.*", "/static/**")), "admin")
				.get(ServerProtocol.WEB);

		assertEquals("/login*.*\n/static/**", plan.request().authExemptPaths());
	}

	@Test
	void anEmptyListSendsNothingAtAll() {
		TunnelMapper.ProtocolTunnel plan = mapper()
				.tunnelPlans(serverWith(WebAuthMethod.SSO, List.of()), "admin")
				.get(ServerProtocol.WEB);

		assertNull(plan.request().authExemptPaths());
	}

	/** Nothing left to make an exception to, so boringproxy is never told about one. */
	@Test
	void anUnprotectedServerSendsNoExemptPathsEvenWhenSomeAreStored() {
		TunnelMapper.ProtocolTunnel plan = mapper()
				.tunnelPlans(serverWith(WebAuthMethod.NONE, List.of("/login*.*")), "admin")
				.get(ServerProtocol.WEB);

		assertNull(plan.request().authExemptPaths());
	}
}
