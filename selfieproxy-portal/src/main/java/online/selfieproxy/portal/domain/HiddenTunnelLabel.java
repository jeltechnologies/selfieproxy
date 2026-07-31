package online.selfieproxy.portal.domain;

/**
 * Derives the base DNS label for a hidden protocol tunnel (Terminal/RemoteDesktop/PortForwarding)
 * from the Application's own full FQDN -- not just its subdomain, since a subdomain alone can
 * repeat across different registered domains and would collide. Dots aren't valid within a single
 * DNS label, so they're replaced with hyphens; the protocol's own defining port is appended so the
 * label reads as e.g. "music-jeltechnologies-com-1234" -- readable, and distinct per Application
 * even before any collision-suffix is considered (see HiddenTunnelFqdnAssigner).
 */
public final class HiddenTunnelLabel {

	// RFC1123's 63-char label max, minus room for a "-<n>" collision suffix (see HiddenTunnelFqdnAssigner).
	private static final int MAX_LENGTH = 59;

	private HiddenTunnelLabel() {
	}

	public static String derive(String appFqdn, int port) {
		String suffix = "-" + port;
		String base = appFqdn.replace('.', '-');
		int maxBaseLength = MAX_LENGTH - suffix.length();
		if (base.length() > maxBaseLength) {
			base = strip(base.substring(0, maxBaseLength));
		}
		return base + suffix;
	}

	private static String strip(String label) {
		int end = label.length();
		while (end > 0 && label.charAt(end - 1) == '-') {
			end--;
		}
		return label.substring(0, end);
	}
}
