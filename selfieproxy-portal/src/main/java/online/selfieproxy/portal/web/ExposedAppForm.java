package online.selfieproxy.portal.web;

import online.selfieproxy.portal.domain.ExposedAppType;
import online.selfieproxy.portal.domain.NetworkServiceMode;
import online.selfieproxy.portal.domain.Protocol;
import online.selfieproxy.portal.domain.TlsMode;

/**
 * What edit-app.html submits. The original subdomain (when editing) comes from the URL path
 * variable instead. secret is the plaintext password for an SSH/RDP/VNC-mode Network Service --
 * left blank on an edit to keep the previously stored credential unchanged (see
 * ExposedAppController.create/update).
 */
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
		String domain,
		NetworkServiceMode mode,
		String username,
		String secret,
		Boolean ignoreCertificate) {
}
