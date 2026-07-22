package online.selfieproxy.portal.domain;

/**
 * The four modes a Network Service exposed app can run in. Only meaningful
 * when ExposedAppType is NETWORK_SERVICE. RAW_TCP is the original, still
 * internet-reachable (allow-external-tcp true) behavior; the other three are
 * a browser SSH/RDP/VNC session (allow-external-tcp false, reached through
 * selfieproxy-remote-console's own console domain instead of a public URL --
 * see TunnelMapper/DashboardController).
 */
public enum NetworkServiceMode {

	RAW_TCP("TCP"),
	SSH("Terminal Access: SSH"),
	RDP("Desktop Access: RDP"),
	VNC("Desktop Access: VNC");

	private final String label;

	NetworkServiceMode(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** Null for RAW_TCP -- its port is whatever the user enters, with no protocol-specific default. */
	public Integer defaultPort() {
		return switch (this) {
			case RAW_TCP -> null;
			case SSH -> 22;
			case RDP -> 3389;
			case VNC -> 5900;
		};
	}
}
