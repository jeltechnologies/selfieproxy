package online.selfieproxy.remoteconsole.domain;

/** Mirrors selfieproxy-portal's own enum of the same name -- see that module's javadoc for why this is a deliberate small duplication rather than a shared library between two independently-built Maven projects. */
public enum RemoteConsoleProtocol {
	SSH,
	RDP,
	VNC
}
