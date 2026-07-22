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

	/**
	 * The doc's "Result" field: the URL for a Web Application, or "domain:port" for a Network
	 * Service. Only meaningful for a RAW_TCP-mode Network Service -- an SSH/RDP/VNC-mode one is
	 * never internet-reachable and dashboard.html never calls this for those rows, rendering its
	 * own Connect button in the "Connect" column instead (see DashboardController).
	 */
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
			boolean rawTcp = app.effectiveMode() == NetworkServiceMode.RAW_TCP;
			// SSH/RDP/VNC mode: tunnelPort left null so boringproxy auto-assigns one, and
			// allowExternalTcp false binds its listener to 127.0.0.1 on the server host --
			// never internet-reachable, only selfieproxy-remote-console (network_mode: host)
			// can dial it. See root CLAUDE.md's "Running" section.
			tunnelPort = rawTcp ? app.exposedPort() : null;
			allowExternalTcp = rawTcp;
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

		// Any passthrough tunnel is a Network Service, whether or not it's internet-reachable --
		// allowExternalTcp alone used to gate this, which is exactly why an SSH/RDP/VNC-mode
		// tunnel's own passthrough-but-not-allowExternalTcp shape fell through to the
		// WEB_APPLICATION branch below and showed up mis-typed on the Applications list. The
		// true mode (RAW_TCP vs SSH/RDP/VNC) is never recoverable from tunnel data alone --
		// defaulted to RAW_TCP here and overlaid from the stored record by ExposedAppStore.reconcile.
		if ("passthrough".equals(tunnel.tlsTermination())) {
			return new ExposedApp(subdomain, null, tunnel.agentName(), ExposedAppType.NETWORK_SERVICE,
					null, tunnel.clientAddress(), tunnel.clientPort(), tunnel.tunnelPort(), null, false, domain,
					NetworkServiceMode.RAW_TCP, null, null, false);
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
				tunnel.ssoProtected(), domain, null, null, null, false);
	}
}
