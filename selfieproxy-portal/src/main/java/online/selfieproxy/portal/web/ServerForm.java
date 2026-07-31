package online.selfieproxy.portal.web;

import online.selfieproxy.portal.domain.PortForwardingProtocol;
import online.selfieproxy.portal.domain.Protocol;
import online.selfieproxy.portal.domain.RemoteDesktopProtocol;

/**
 * What edit-server.html submits. The original subdomain (when editing) comes from the URL path
 * variable instead. homelabName/host are shared across every protocol. Each row below submits its
 * own enabled/protocol/port/credential fields; terminalSecret/remoteDesktopSecret are the
 * plaintext passwords -- left blank on an edit to keep the previously stored credential unchanged
 * (see ServerController.toServer).
 */
public record ServerForm(
		String subdomain,
		String domain,
		String homelabName,
		String host,
		// Web row
		Boolean webEnabled,
		Protocol webProtocol,
		Integer webPort,
		Boolean webSsoProtected,
		// Terminal row
		Boolean terminalEnabled,
		Integer terminalPort,
		String terminalUsername,
		String terminalSecret,
		// Remote Desktop row
		Boolean remoteDesktopEnabled,
		RemoteDesktopProtocol remoteDesktopProtocol,
		Integer remoteDesktopPort,
		String remoteDesktopUsername,
		String remoteDesktopSecret,
		Boolean remoteDesktopIgnoreCertificate,
		// Port Forwarding row
		Boolean portForwardingEnabled,
		PortForwardingProtocol portForwardingProtocol,
		Integer portForwardingPublicPort,
		Integer portForwardingTargetPort) {
}
