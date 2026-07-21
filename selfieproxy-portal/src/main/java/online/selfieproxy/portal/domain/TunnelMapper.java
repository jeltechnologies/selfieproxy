package online.selfieproxy.portal.domain;

import java.util.Comparator;

import org.springframework.stereotype.Component;

import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;

/**
 * Translates between Selfie Proxy's ExposedApp/Homelab model and
 * BoringProxy's Tunnel/CreateTunnelRequest, per the TLS-termination mapping
 * table in the project plan (derived from selfieproxy-portal/CLAUDE.md's own
 * parenthetical BoringProxy-mode hints).
 */
@Component
public class TunnelMapper {

	private static final String HTTPS_PREFIX = "https://";

	private final DomainService domainService;
	private final ExposedAppStore exposedAppStore;

	public TunnelMapper(DomainService domainService, ExposedAppStore exposedAppStore) {
		this.domainService = domainService;
		this.exposedAppStore = exposedAppStore;
	}

	public String fqdn(ExposedApp app) {
		return app.fqdn();
	}

	/** The doc's "Result" field: the URL for a Web Application, or "domain:port" for a Network Service. */
	public String result(ExposedApp app) {
		if (app.isNetworkService()) {
			return app.domain() + ":" + app.exposedPort();
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
			// an HTTP-only homelab app still be protected with single sign on (see ExposedApp.canProtectWithSso()).
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

	/**
	 * Splits a live BoringProxy tunnel's flat FQDN into subdomain + domain. Checks
	 * ExposedAppStore for an already-reconciled record by the tunnel's own FQDN first -- that
	 * record's stored subdomain/domain fields are authoritative and require no guessing, and
	 * critically remain correct even after the app's domain is later removed from the Domains
	 * page (an orphaned domain is still a perfectly valid, still-functioning app -- see
	 * DomainsController's delete(), which has no cascade). Only a tunnel Selfie Proxy has never
	 * reconciled before (eg. one created through the legacy BoringProxy UI) falls through to
	 * guessing the split from the longest currently-registered domain (primary or any secondary,
	 * see DomainService) that's a suffix of the FQDN -- and even then, an unmatched FQDN never
	 * throws (which would take down the entire Applications list for every other app over one bad
	 * tunnel); it degrades to treating the whole FQDN as the domain with an empty subdomain.
	 */
	public ExposedApp toExposedApp(TunnelDto tunnel) {
		ExposedApp stored = exposedAppStore.find(tunnel.domain());
		String domain;
		String subdomain;
		if (stored != null) {
			domain = stored.domain();
			subdomain = stored.subdomain();
		} else {
			domain = domainService.allDomains().stream()
					.filter(d -> tunnel.domain().equalsIgnoreCase(d.name())
							|| tunnel.domain().toLowerCase().endsWith("." + d.name().toLowerCase()))
					.max(Comparator.comparingInt(d -> d.name().length()))
					.map(DomainService.Domain::name)
					.orElse(tunnel.domain());
			subdomain = tunnel.domain().equalsIgnoreCase(domain) ? ""
					: tunnel.domain().substring(0, tunnel.domain().length() - domain.length() - 1);
		}

		if ("passthrough".equals(tunnel.tlsTermination()) && tunnel.allowExternalTcp()) {
			return new ExposedApp(subdomain, null, tunnel.agentName(), ExposedAppType.NETWORK_SERVICE,
					null, tunnel.clientAddress(), tunnel.clientPort(), tunnel.tunnelPort(), null, false, domain);
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
				tunnel.ssoProtected(), domain);
	}
}
