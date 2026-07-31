package online.selfieproxy.portal.domain;

/**
 * Derives the base DNS label for a hidden protocol tunnel (Terminal/RemoteDesktop/PortForwarding)
 * from the Application's own full FQDN -- not just its subdomain, since a subdomain alone can
 * repeat across different registered domains and would collide. Dots aren't valid within a single
 * DNS label, so they're replaced with hyphens; a suffix is appended so the label reads as e.g.
 * "music-jeltechnologies-com-terminal" -- readable, and distinct per Application even before any
 * collision-suffix is considered (see HiddenTunnelFqdnAssigner). Terminal/RemoteDesktop use a
 * fixed protocol-name suffix ("terminal"/"remotedesktop") rather than their own port number, since
 * an Application only ever has one of each, so no port is needed for uniqueness -- and printing
 * the port would otherwise leak which service is listening (SSH vs. RDP/VNC) to anyone who can
 * read the reverseproxy agent's own tunnel logs, or scan Certificate Transparency logs for the
 * primary domain. Port Forwarding keeps a port-number suffix (the public port), since that port is
 * already meant to be public/scannable (the edit page warns about it) and, now that an Application
 * can forward several ports at once, it's what keeps each entry's hidden name distinct from its
 * siblings.
 */
public final class HiddenTunnelLabel {

	// RFC1123's 63-char label max, minus room for a "-<n>" collision suffix (see HiddenTunnelFqdnAssigner).
	private static final int MAX_LENGTH = 59;

	private HiddenTunnelLabel() {
	}

	public static String derive(String appFqdn, String suffix) {
		String dashedSuffix = "-" + suffix;
		String base = appFqdn.replace('.', '-');
		int maxBaseLength = MAX_LENGTH - dashedSuffix.length();
		if (base.length() > maxBaseLength) {
			base = strip(base.substring(0, maxBaseLength));
		}
		return base + dashedSuffix;
	}

	private static String strip(String label) {
		int end = label.length();
		while (end > 0 && label.charAt(end - 1) == '-') {
			end--;
		}
		return label.substring(0, end);
	}
}
