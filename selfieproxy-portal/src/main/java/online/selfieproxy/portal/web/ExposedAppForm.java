package online.selfieproxy.portal.web;

import online.selfieproxy.portal.domain.ExposedAppType;
import online.selfieproxy.portal.domain.Protocol;
import online.selfieproxy.portal.domain.TlsMode;

/** What edit-app.html submits. The original subdomain (when editing) comes from the URL path variable instead. */
public record ExposedAppForm(
		String subdomain,
		String name,
		String homelabName,
		ExposedAppType type,
		Protocol protocol,
		String host,
		Integer port,
		Integer exposedPort,
		TlsMode tlsMode,
		Boolean ssoProtected,
		String domain) {
}
