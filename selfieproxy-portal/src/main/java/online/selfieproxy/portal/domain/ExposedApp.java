package online.selfieproxy.portal.domain;

/**
 * Selfie Proxy's own model of a published app, mapped to/from a BoringProxy
 * "Tunnel" by {@link TunnelMapper}. Deliberately avoids BoringProxy's own
 * vocabulary (Client/Tunnel) in field names, per selfieproxy-portal/CLAUDE.md.
 *
 * @param subdomain    the app's Selfie Proxy identifier -- the label suffixed with {@link #domain} to form the FQDN
 * @param name         only meaningful when type is NETWORK_SERVICE -- a free-text label; not unique, not part of the domain
 * @param exposedPort  only set when type is NETWORK_SERVICE
 * @param protocol     only meaningful when type is WEB_APPLICATION (Network Service is always TCP)
 * @param tlsMode      only set when type is WEB_APPLICATION and protocol is HTTPS
 * @param ssoProtected whether boringproxy gates this app behind the configured OIDC issuer; only ever true when {@link #canProtectWithSso()} holds, since boringproxy only ever parses HTTP (and so can enforce single sign on) for "server"-termination tunnels -- see TlsMode.MANAGED
 * @param domain       which registered domain (the primary domain or a secondary one, see DomainService) this app is exposed on
 */
public record ExposedApp(
		String subdomain,
		String name,
		String homelabName,
		ExposedAppType type,
		Protocol protocol,
		String host,
		int port,
		Integer exposedPort,
		TlsMode tlsMode,
		boolean ssoProtected,
		String domain) {

	public String fqdn() {
		return subdomain + "." + domain;
	}

	public boolean isWebApplication() {
		return type == ExposedAppType.WEB_APPLICATION;
	}

	public boolean isNetworkService() {
		return type == ExposedAppType.NETWORK_SERVICE;
	}

	public boolean showsAdvancedSettings() {
		return isWebApplication() && protocol == Protocol.HTTPS;
	}

	public TlsMode effectiveTlsMode() {
		return tlsMode != null ? tlsMode : TlsMode.MANAGED;
	}

	/**
	 * True whenever the tunnel ends up "server"-terminated -- the only TLS-termination mode boringproxy
	 * itself HTTP-parses, so the only one it can gate with single sign on. That's always true for a plain HTTP
	 * homelab app (Selfie Proxy still terminates the public TLS connection itself, see TunnelMapper),
	 * and true for an HTTPS homelab app only under "Server HTTPS" (TlsMode.MANAGED) -- BYO_CERT/
	 * HOP_BY_HOP are HTTPS too but never HTTP-parsed at the server, so single sign on can't be enforced for them.
	 */
	public boolean canProtectWithSso() {
		return isWebApplication() && (protocol == Protocol.HTTP || effectiveTlsMode() == TlsMode.MANAGED);
	}
}
