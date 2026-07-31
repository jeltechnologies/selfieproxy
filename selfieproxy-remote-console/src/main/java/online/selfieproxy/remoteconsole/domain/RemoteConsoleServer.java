package online.selfieproxy.remoteconsole.domain;

/**
 * Deliberately partial mirror of selfieproxy-portal's own Server -- just the two protocol
 * facets this service ever bridges. Either or both can be null (that protocol isn't enabled for
 * this Application). See RemoteConsoleStore for how these get flattened into a hidden-FQDN index.
 */
public record RemoteConsoleServer(RemoteConsoleTerminal terminal, RemoteConsoleRemoteDesktop remoteDesktop) {
}
