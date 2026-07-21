package online.selfieproxy.portal.domain;

/**
 * Selfie Proxy's own model of a browser SSH/RDP/VNC console, backed by an
 * {@code AllowExternalTcp: false} BoringProxy Tunnel -- see
 * selfieproxy-portal/CLAUDE.md's "Remote consoles" section and the project
 * plan for why that flag (never internet-reachable, only this server's own
 * host network namespace) is the whole point of this feature.
 *
 * @param id                stable identity (no FQDN concept is ever shown to the user)
 * @param name               user-facing label, shown in the list
 * @param homelabName        Agent name, same dropdown as an Exposed App (minus "This Server")
 * @param protocol           SSH, RDP, or VNC
 * @param host               target host/IP within the homelab
 * @param port               target port within the homelab
 * @param username           nullable -- VNC often has no username at all
 * @param authMode           PASSWORD or PRIVATE_KEY (SSH only; RDP/VNC are always PASSWORD)
 * @param encryptedSecret    base64 AES-GCM ciphertext of the password or private key, nullable
 * @param ignoreCertificate  RDP/VNC only -- accept the target's self-signed certificate
 * @param internalFqdn       the generated {@code rc-<uuid>.<primaryDomain>} BoringProxy tunnel
 *                           key -- always the primary domain, never user-chosen or shown, exactly
 *                           like {@code proxylistener}/{@code selfieproxy}/{@code auth}/{@code console}
 * @param tunnelPort         BoringProxy's resolved {@code tunnel_port} for internalFqdn -- what
 *                           selfieproxy-guacd dials at {@code 127.0.0.1:<tunnelPort>}
 */
public record RemoteConsole(
		String id,
		String name,
		String homelabName,
		RemoteConsoleProtocol protocol,
		String host,
		int port,
		String username,
		RemoteConsoleAuthMode authMode,
		String encryptedSecret,
		boolean ignoreCertificate,
		String internalFqdn,
		int tunnelPort) {
}
