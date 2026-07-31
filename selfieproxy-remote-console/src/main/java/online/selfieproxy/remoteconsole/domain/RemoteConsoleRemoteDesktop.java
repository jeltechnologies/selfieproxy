package online.selfieproxy.remoteconsole.domain;

/** Mirrors selfieproxy-portal's own RemoteDesktopConfig -- see RemoteConsoleServer/RemoteConsoleStore. */
public record RemoteConsoleRemoteDesktop(String fqdn, RemoteConsoleProtocol protocol, Integer exposedPort,
		String username, String encryptedSecret, boolean ignoreCertificate) {
}
