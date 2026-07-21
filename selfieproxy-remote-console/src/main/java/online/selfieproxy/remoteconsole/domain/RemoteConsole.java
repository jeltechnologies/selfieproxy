package online.selfieproxy.remoteconsole.domain;

/**
 * Read-only mirror of selfieproxy-portal's own RemoteConsole record -- this
 * service never writes remote-consoles.json, only reads it (shared /data
 * volume) at connect time. See that module's javadoc for the field meanings.
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
