package online.selfieproxy.portal.domain;

/** The two Remote Desktop protocols a Remote Desktop-enabled Application can pick between. */
public enum RemoteDesktopProtocol {

	RDP(3389),
	VNC(5900);

	private final int defaultPort;

	RemoteDesktopProtocol(int defaultPort) {
		this.defaultPort = defaultPort;
	}

	public int defaultPort() {
		return defaultPort;
	}
}
