package online.selfieproxy.remoteconsole.domain;

/** Mirrors selfieproxy-portal's own TerminalConfig -- see RemoteConsoleServer/RemoteConsoleStore. */
public record RemoteConsoleTerminal(String fqdn, Integer exposedPort, String username, String encryptedSecret) {
}
