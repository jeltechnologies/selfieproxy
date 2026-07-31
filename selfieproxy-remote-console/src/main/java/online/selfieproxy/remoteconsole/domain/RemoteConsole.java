package online.selfieproxy.remoteconsole.domain;

/**
 * Read-only view of one protocol facet (Terminal or Remote Desktop) of a selfieproxy-portal
 * Application, trimmed to exactly what this service needs to dial guacd/SSH -- this service never
 * writes servers.json, only reads it (shared /data volume) at connect time. The portal's own
 * Application record carries a shared host/port too, but neither is ever actually used to dial here
 * (both GuacamoleWebSocketHandler and SshWebSocketHandler always dial 127.0.0.1:exposedPort, the
 * already-resolved boringproxy tunnel port -- see root CLAUDE.md's host-networking rationale), so
 * this mirror omits them entirely.
 *
 * @param mode              SSH (for a Terminal facet) or the stored RDP/VNC choice (for a Remote Desktop facet)
 * @param exposedPort       the boringproxy-assigned tunnel port for this protocol -- see tunnelPort()
 */
public record RemoteConsole(
		RemoteConsoleProtocol mode,
		Integer exposedPort,
		String username,
		String encryptedSecret,
		boolean ignoreCertificate) {

	public int tunnelPort() {
		return exposedPort == null ? 0 : exposedPort;
	}
}
