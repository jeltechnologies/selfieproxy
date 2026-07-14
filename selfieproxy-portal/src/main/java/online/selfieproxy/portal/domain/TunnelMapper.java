package online.selfieproxy.portal.domain;

import org.springframework.stereotype.Component;

import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.BoringProxyProperties;

/**
 * Translates between Selfie Proxy's ExposedApp/Homelab model and
 * BoringProxy's Tunnel/CreateTunnelRequest, per the TLS-termination mapping
 * table in the project plan (derived from selfieproxy-portal/CLAUDE.md's own
 * parenthetical BoringProxy-mode hints).
 */
@Component
public class TunnelMapper {

	private static final String HTTPS_PREFIX = "https://";

	private final BoringProxyProperties properties;

	public TunnelMapper(BoringProxyProperties properties) {
		this.properties = properties;
	}

	public String fqdn(ExposedApp app) {
		return properties.fqdn(app.subdomain());
	}

	/** The doc's "Result" field: the URL for a Web Application, or "domain:port" for a Network Service. */
	public String result(ExposedApp app) {
		if (app.isNetworkService()) {
			return properties.domain() + ":" + app.exposedPort();
		}
		return HTTPS_PREFIX + fqdn(app);
	}

	/** Only Web Application results are rendered as a clickable link. */
	public boolean resultIsLink(ExposedApp app) {
		return app.isWebApplication();
	}

	public CreateTunnelRequestDto toCreateTunnelRequest(ExposedApp app, String owner) {
		String tlsTermination;
		String clientAddr = app.host();
		Integer tunnelPort = null;
		Boolean allowExternalTcp = null;

		if (app.isNetworkService()) {
			tlsTermination = "passthrough";
			tunnelPort = app.exposedPort();
			allowExternalTcp = true;
		} else if (app.protocol() == Protocol.HTTP) {
			// Selfie Proxy still terminates the public TLS connection itself (managed cert, same as
			// MANAGED/"Server HTTPS") and forwards plain HTTP onward -- proxyRequest defaults to
			// upstreamScheme "http" whenever clientAddr has no "https://" prefix. This is what lets
			// an HTTP-only homelab app still be SSO-protected (see ExposedApp.canProtectWithSso()).
			tlsTermination = "server";
		} else {
			clientAddr = HTTPS_PREFIX + app.host();
			tlsTermination = switch (app.effectiveTlsMode()) {
				case MANAGED -> "server";
				case BYO_CERT -> "client-tls";
				case HOP_BY_HOP -> "server-tls";
			};
		}

		return new CreateTunnelRequestDto(
				fqdn(app),
				owner,
				app.homelabName(), // Agent name, matches the Homelab name 1:1
				app.port(),
				clientAddr,
				tunnelPort,
				allowExternalTcp,
				null,
				null,
				null,
				tlsTermination,
				app.canProtectWithSso() ? app.ssoProtected() : null,
				null,
				null);
	}

	public ExposedApp toExposedApp(TunnelDto tunnel) {
		String suffix = "." + properties.domain();
		String subdomain = tunnel.domain().substring(0, tunnel.domain().length() - suffix.length());

		if ("passthrough".equals(tunnel.tlsTermination()) && tunnel.allowExternalTcp()) {
			return new ExposedApp(subdomain, null, tunnel.agentName(), ExposedAppType.NETWORK_SERVICE,
					null, tunnel.clientAddress(), tunnel.clientPort(), tunnel.tunnelPort(), null, false);
		}

		boolean https = tunnel.clientAddress() != null && tunnel.clientAddress().startsWith(HTTPS_PREFIX);
		String host = https ? tunnel.clientAddress().substring(HTTPS_PREFIX.length()) : tunnel.clientAddress();

		TlsMode tlsMode = switch (tunnel.tlsTermination()) {
			case "server" -> TlsMode.MANAGED;
			case "client-tls" -> TlsMode.BYO_CERT;
			case "server-tls" -> TlsMode.HOP_BY_HOP;
			default -> null;
		};

		return new ExposedApp(subdomain, null, tunnel.agentName(), ExposedAppType.WEB_APPLICATION,
				https ? Protocol.HTTPS : Protocol.HTTP, host, tunnel.clientPort(), null, tlsMode,
				tunnel.ssoProtected());
	}
}
