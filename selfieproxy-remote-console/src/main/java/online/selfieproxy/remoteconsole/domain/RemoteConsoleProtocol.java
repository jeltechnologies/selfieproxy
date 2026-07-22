package online.selfieproxy.remoteconsole.domain;

/**
 * Mirrors selfieproxy-portal's own NetworkServiceMode enum -- see that module's javadoc for why
 * this is a deliberate small duplication rather than a shared library between two independently-
 * built Maven projects. RAW_TCP is included purely so Jackson can deserialize exposed-apps.json's
 * "mode" field for every entry in the file without failing on an unrecognized constant -- a
 * RAW_TCP (or null-mode) entry is always filtered out by RemoteConsoleStore before it ever reaches
 * GuacamoleWebSocketHandler, which only ever switches on SSH/RDP/VNC.
 */
public enum RemoteConsoleProtocol {
	RAW_TCP,
	SSH,
	RDP,
	VNC
}
