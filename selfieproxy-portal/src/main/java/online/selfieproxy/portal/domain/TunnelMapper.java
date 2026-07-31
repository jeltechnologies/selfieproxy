package online.selfieproxy.portal.domain;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;

/**
 * Translates an Application's up-to-4 enabled protocols into their independent boringproxy
 * CreateTunnelRequests. A pure mapper with no store/domain dependencies -- unlike the
 * single-protocol model this replaces, there's no reverse "reconstruct an Server from a live
 * Tunnel" direction anymore: ServerStore is now the sole source of truth for an Application's
 * shape (see its own javadoc), tunnels are just a derived side-effect kept in sync on save.
 */
@Component
public class TunnelMapper {

	private static final String HTTPS_PREFIX = "https://";

	public record ProtocolTunnel(String fqdn, CreateTunnelRequestDto request) {
	}

	public String fqdn(Server server) {
		return server.fqdn();
	}

	/** Only entries for the server's currently-enabled protocols. */
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
		if (server.hasPortForwarding()) {
			plans.put(ServerProtocol.PORT_FORWARDING, portForwardingTunnel(server, owner));
		}
		return plans;
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
		CreateTunnelRequestDto request = new CreateTunnelRequestDto(
				server.fqdn(), owner, server.homelabName(), web.port(), clientAddr, null, null, null, null, null,
				"server", web.ssoProtected(), null, null);
		return new ProtocolTunnel(server.fqdn(), request);
	}

	/** allowExternalTcp false binds the tunnel's listener to 127.0.0.1 on the server host -- never internet-reachable, only selfieproxy-remote-console (network_mode: host) can dial it. */
	private ProtocolTunnel terminalTunnel(Server server, String owner) {
		TerminalConfig terminal = server.terminal();
		CreateTunnelRequestDto request = new CreateTunnelRequestDto(
				terminal.fqdn(), owner, server.homelabName(), terminal.port(), server.host(), null, false, null, null, null,
				"passthrough", null, null, null);
		return new ProtocolTunnel(terminal.fqdn(), request);
	}

	private ProtocolTunnel remoteDesktopTunnel(Server server, String owner) {
		RemoteDesktopConfig remoteDesktop = server.remoteDesktop();
		CreateTunnelRequestDto request = new CreateTunnelRequestDto(
				remoteDesktop.fqdn(), owner, server.homelabName(), remoteDesktop.port(), server.host(), null, false, null,
				null, null, "passthrough", null, null, null);
		return new ProtocolTunnel(remoteDesktop.fqdn(), request);
	}

	/** allowExternalTcp true -- the one hidden protocol that's genuinely internet-reachable, at publicPort. */
	private ProtocolTunnel portForwardingTunnel(Server server, String owner) {
		PortForwardingConfig portForwarding = server.portForwarding();
		CreateTunnelRequestDto request = new CreateTunnelRequestDto(
				portForwarding.fqdn(), owner, server.homelabName(), portForwarding.targetPort(), server.host(),
				portForwarding.publicPort(), true, null, null, null, "passthrough", null, null, null);
		return new ProtocolTunnel(portForwarding.fqdn(), request);
	}
}
