package online.selfieproxy.portal.domain;

/**
 * Selfie Proxy's own model of a published app, mapped to/from a BoringProxy
 * "Tunnel" by {@link TunnelMapper}. Deliberately avoids BoringProxy's own
 * vocabulary (Client/Tunnel) in field names, per selfieproxy.md.
 *
 * @param subdomain    the app's Selfie Proxy identifier and, for the default (non-ownDomain) mode, the label suffixed with the shared DOMAIN; when ownDomain is true this holds the complete FQDN verbatim instead
 * @param ownDomain    true if subdomain already holds a complete domain the user owns (eg. "www.jeltechnologies.com"), rather than a label to suffix with the shared DOMAIN
 * @param name         only meaningful when type is NETWORK_SERVICE -- a free-text label; not unique, not part of the domain
 * @param exposedPort  only set when type is NETWORK_SERVICE
 * @param protocol     only meaningful when type is WEB_APPLICATION (Network Service is always TCP)
 * @param tlsMode      only set when type is WEB_APPLICATION and protocol is HTTPS
 */
public record ExposedApp(
		String subdomain,
		boolean ownDomain,
		String name,
		String homelabName,
		ExposedAppType type,
		Protocol protocol,
		String host,
		int port,
		Integer exposedPort,
		TlsMode tlsMode) {

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
}
