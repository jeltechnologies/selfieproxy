package online.selfieproxy.remoteconsole.domain;

/**
 * Read-only mirror of selfieproxy-portal's own ExposedApp record, trimmed to the fields this
 * service actually needs -- this service never writes exposed-apps.json, only reads it (shared
 * /data volume) at connect time. Every other property in that file's JSON (subdomain, protocol,
 * tlsMode, ssoProtected, domain, and Jackson's own derived isX()-style properties) is silently
 * ignored by RemoteConsoleStore's ObjectMapper rather than mirrored here.
 *
 * @param type          "WEB_APPLICATION" or "NETWORK_SERVICE" -- only the latter, with mode SSH/RDP/VNC, is ever returned by RemoteConsoleStore.find
 * @param exposedPort   the boringproxy-assigned tunnel port for this app -- see tunnelPort()
 * @param mode          RAW_TCP, or the SSH/RDP/VNC mode actually bridged here
 */
public record RemoteConsole(
		String name,
		String homelabName,
		String type,
		String host,
		int port,
		Integer exposedPort,
		RemoteConsoleProtocol mode,
		String username,
		String encryptedSecret,
		boolean ignoreCertificate) {

	public int tunnelPort() {
		return exposedPort == null ? 0 : exposedPort;
	}
}
