package online.selfieproxy.portal.domain;

public enum RemoteConsoleProtocol {
	SSH,
	RDP,
	VNC;

	public int defaultPort() {
		return switch (this) {
			case SSH -> 22;
			case RDP -> 3389;
			case VNC -> 5900;
		};
	}
}
