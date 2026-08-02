package online.selfieproxy.portal.web;

import java.util.List;

import online.selfieproxy.portal.domain.Protocol;
import online.selfieproxy.portal.domain.RemoteDesktopProtocol;

/**
 * What edit-server.html submits. The original subdomain (when editing) comes from the URL path
 * variable instead. homelabName/host are shared across every protocol. Each row below submits its
 * own enabled/protocol/port/credential fields; terminalSecret/remoteDesktopSecret are the
 * plaintext passwords -- left blank on an edit to keep the previously stored credential unchanged
 * (see ServerController.toServer). Port Forwarding has no protocol field here -- it's always TCP
 * (see ServerController.toServer), so there's nothing for the admin to choose -- just up to 8
 * repeatable target/public port/description triples, rendered as table rows (see edit-server.html);
 * Spring binds these from same-named, repeated &lt;input&gt;s in document order, so
 * portForwardingTargetPort.get(i)/portForwardingPublicPort.get(i)/portForwardingDescription.get(i)
 * are always the i-th entry.
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
		// Port Forwarding row
		Boolean portForwardingEnabled,
		List<Integer> portForwardingPublicPort,
		List<Integer> portForwardingTargetPort,
		List<String> portForwardingDescription) {
}
