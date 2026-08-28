package online.selfieproxy.portal.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.security.NetworkServiceCredentialCipher;

/**
 * Translates an Application's up-to-4 enabled protocols into their independent boringproxy
 * CreateTunnelRequests. Stateless apart from the credential cipher -- unlike the
 * single-protocol model this replaces, there's no reverse "reconstruct an Server from a live
 * Tunnel" direction anymore: ServerStore is now the sole source of truth for an Application's
 * shape (see its own javadoc), tunnels are just a derived side-effect kept in sync on save.
 * The cipher is needed because a Web protocol's Basic/token credential is stored encrypted
 * (like every other credential here) but boringproxy compares it as plaintext, so this is the
 * one point where it has to be decrypted.
 * Port Forwarding is deliberately kept out of {@link #tunnelPlans} -- it's the one protocol that
 * can produce more than one tunnel per Server (up to 8), so it has its own
 * {@link #portForwardingTunnelPlans} returning a list instead of forcing the other three
 * always-single-tunnel protocols into a list-shaped map they'd never use.
 */
@Component
public class TunnelMapper {

	private static final String HTTPS_PREFIX = "https://";

	private final NetworkServiceCredentialCipher credentials;

	public TunnelMapper(NetworkServiceCredentialCipher credentials) {
		this.credentials = credentials;
	}

	public record ProtocolTunnel(String fqdn, CreateTunnelRequestDto request) {
	}

	public String fqdn(Server server) {
		return server.fqdn();
	}

	/** Only entries for the server's currently-enabled single-tunnel protocols (WEB/TERMINAL/REMOTE_DESKTOP) -- see {@link #portForwardingTunnelPlans} for Port Forwarding. */
	public Map<ServerProtocol, ProtocolTunnel> tunnelPlans(Server server, String owner) {
		Map<ServerProtocol, ProtocolTunnel> plans = new EnumMap<>(ServerProtocol.class);
		if (server.hasWeb()) {
			plans.put(ServerProtocol.WEB, webTunnel(server, owner));
		}
		if (server.hasTerminal()) {
			plans.put(ServerProtocol.TERMINAL, terminalTunnel(server, owner));
		}
		if (server.hasRemoteDesktop()) {
			plans.put(ServerProtocol.REMOTE_DESKTOP, remoteDesktopTunnel(server, owner));
		}
		return plans;
	}

	/** One entry per enabled Port Forwarding port (up to 8), empty if disabled. */
	public List<ProtocolTunnel> portForwardingTunnelPlans(Server server, String owner) {
		if (!server.hasPortForwarding()) {
			return List.of();
		}
		return server.portForwarding().stream()
				.map(entry -> portForwardingTunnel(server, entry, owner))
				.toList();
	}

	/**
	 * Always "server" tls-termination (MANAGED -- Selfie Proxy automatically creates and renews a
	 * signed certificate) for HTTPS; plain HTTP is also always "server"-terminated, with
	 * proxyRequest defaulting to upstreamScheme "http" whenever clientAddr has no "https://"
	 * prefix. This is what lets a Web Application be protected with single sign on either way.
	 */
	private ProtocolTunnel webTunnel(Server server, String owner) {
		WebConfig web = server.web();
		String clientAddr = web.protocol() == Protocol.HTTPS ? HTTPS_PREFIX + server.host() : server.host();
		WebAuthMethod authMethod = web.authMethodOrDefault();

		// Exactly one gate is ever handed to boringproxy. It checks single sign on before it ever
		// reaches a tunnel's own credential gate, so sending two would silently honour only the
		// first -- WebAuthMethod being an exclusive choice is what keeps that from happening.
		boolean basic = authMethod == WebAuthMethod.BASIC;
		boolean token = authMethod == WebAuthMethod.TOKEN;

		CreateTunnelRequestDto request = new CreateTunnelRequestDto(
				server.fqdn(), owner, server.homelabName(), web.port(), clientAddr, null, null,
				basic ? Boolean.TRUE : null,
				basic ? web.basicUsername() : null,
				basic ? credentials.decrypt(web.basicPassword()) : null,
				"server", authMethod == WebAuthMethod.SSO,
				token ? Boolean.TRUE : null,
				token ? web.tokenHeaderName() : null,
				token ? credentials.decrypt(web.tokenValue()) : null,
				null, null);
		return new ProtocolTunnel(server.fqdn(), request);
	}

	/** allowExternalTcp false binds the tunnel's listener to 127.0.0.1 on the server host -- never internet-reachable, only selfieproxy-remote-console (network_mode: host) can dial it. */
	private ProtocolTunnel terminalTunnel(Server server, String owner) {
		TerminalConfig terminal = server.terminal();
		CreateTunnelRequestDto request = new CreateTunnelRequestDto(
				terminal.fqdn(), owner, server.homelabName(), terminal.port(), server.host(), null, false, null, null, null,
				"passthrough", null, null, null, null, null, null);
		return new ProtocolTunnel(terminal.fqdn(), request);
	}

	private ProtocolTunnel remoteDesktopTunnel(Server server, String owner) {
		RemoteDesktopConfig remoteDesktop = server.remoteDesktop();
		CreateTunnelRequestDto request = new CreateTunnelRequestDto(
				remoteDesktop.fqdn(), owner, server.homelabName(), remoteDesktop.port(), server.host(), null, false, null,
				null, null, "passthrough", null, null, null, null, null, null);
		return new ProtocolTunnel(remoteDesktop.fqdn(), request);
	}

	/** allowExternalTcp true -- the one hidden protocol that's genuinely internet-reachable, at publicPort. */
	private ProtocolTunnel portForwardingTunnel(Server server, PortForwardingConfig entry, String owner) {
		CreateTunnelRequestDto request = new CreateTunnelRequestDto(
				entry.fqdn(), owner, server.homelabName(), entry.targetPort(), server.host(),
				entry.publicPort(), true, null, null, null, "passthrough", null, null, null, null, null, null);
		return new ProtocolTunnel(entry.fqdn(), request);
	}
}
