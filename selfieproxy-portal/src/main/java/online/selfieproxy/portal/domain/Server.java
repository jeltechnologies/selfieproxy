package online.selfieproxy.portal.domain;

import java.util.List;

/**
 * Selfie Proxy's own model of a published Application. One Application can simultaneously expose
 * up to 4 protocols -- Web, Terminal, Remote Desktop, and Port Forwarding -- each present here as
 * its own nullable config record, each becoming its own independent boringproxy tunnel (see
 * TunnelMapper). Deliberately avoids BoringProxy's own vocabulary (Client/Tunnel) in field names,
 * per selfieproxy-portal/CLAUDE.md.
 *
 * @param subdomain      the Application's own Selfie Proxy identifier -- the label suffixed with
 *                       {@link #domain} to form the FQDN, or blank/null to expose it at the bare domain
 *                       itself (apex). Always present, regardless of which protocols are enabled.
 * @param domain         which registered domain (the primary domain or a secondary one, see DomainService)
 *                       this Application is exposed on -- also the domain Port Forwarding's own hidden
 *                       tunnel lives under, since it's a public passthrough tunnel
 * @param homelabName    one Homelab, shared by every protocol this Application has enabled
 * @param host           the homelab-side host/IP, shared by every protocol this Application has enabled
 * @param web            Web (HTTP/HTTPS) settings, or null if Web isn't enabled
 * @param terminal       Terminal (SSH) settings, or null if Terminal isn't enabled
 * @param remoteDesktop  Remote Desktop (RDP/VNC) settings, or null if Remote Desktop isn't enabled
 * @param portForwarding up to 8 Port Forwarding (TCP) entries, one per forwarded port -- never a
 *                       port range, each is its own independent boringproxy tunnel; null or empty
 *                       means Port Forwarding isn't enabled
 */
public record Server(
		String subdomain,
		String domain,
		String homelabName,
		String host,
		WebConfig web,
		TerminalConfig terminal,
		RemoteDesktopConfig remoteDesktop,
		List<PortForwardingConfig> portForwarding) {

	public String fqdn() {
		return subdomain == null || subdomain.isBlank() ? domain : subdomain + "." + domain;
	}

	public boolean hasWeb() {
		return web != null;
	}

	public boolean hasTerminal() {
		return terminal != null;
	}

	public boolean hasRemoteDesktop() {
		return remoteDesktop != null;
	}

	public boolean hasPortForwarding() {
		return portForwarding != null && !portForwarding.isEmpty();
	}

	/** Same record with terminal's exposedPort replaced -- see ServerController.syncTunnels. */
	public Server withTerminalExposedPort(Integer newExposedPort) {
		return new Server(subdomain, domain, homelabName, host, web,
				terminal.withExposedPort(newExposedPort), remoteDesktop, portForwarding);
	}

	/** Same record with remoteDesktop's exposedPort replaced -- see ServerController.syncTunnels. */
	public Server withRemoteDesktopExposedPort(Integer newExposedPort) {
		return new Server(subdomain, domain, homelabName, host, web,
				terminal, remoteDesktop.withExposedPort(newExposedPort), portForwarding);
	}

	/** Same record with terminal's credential replaced -- used when a credential is entered on first Connect. */
	public Server withTerminalCredential(String newEncryptedSecret) {
		return new Server(subdomain, domain, homelabName, host, web,
				terminal.withEncryptedSecret(newEncryptedSecret), remoteDesktop, portForwarding);
	}

	/** Same record with remoteDesktop's credential replaced -- used when a credential is entered on first Connect. */
	public Server withRemoteDesktopCredential(String newEncryptedSecret) {
		return new Server(subdomain, domain, homelabName, host, web,
				terminal, remoteDesktop.withEncryptedSecret(newEncryptedSecret), portForwarding);
	}

	/** Same record with both credential slots cleared -- used when building a configuration export (see BackupService). */
	public Server withoutSecrets() {
		return new Server(subdomain, domain, homelabName, host, web,
				terminal == null ? null : terminal.withoutSecret(),
				remoteDesktop == null ? null : remoteDesktop.withoutSecret(),
				portForwarding);
	}
}
