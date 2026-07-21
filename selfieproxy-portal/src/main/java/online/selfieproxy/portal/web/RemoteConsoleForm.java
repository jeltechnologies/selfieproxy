package online.selfieproxy.portal.web;

import online.selfieproxy.portal.domain.RemoteConsoleAuthMode;
import online.selfieproxy.portal.domain.RemoteConsoleProtocol;

/**
 * What edit-remote-console.html submits. secret is the plaintext password or
 * private key -- left blank on an edit to keep the previously stored
 * credential unchanged (see RemoteConsoleController.toRemoteConsole).
 */
public record RemoteConsoleForm(
		String name,
		String homelabName,
		RemoteConsoleProtocol protocol,
		String host,
		Integer port,
		String username,
		RemoteConsoleAuthMode authMode,
		String secret,
		Boolean ignoreCertificate) {
}
