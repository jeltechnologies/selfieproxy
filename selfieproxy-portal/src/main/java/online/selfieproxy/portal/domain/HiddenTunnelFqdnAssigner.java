package online.selfieproxy.portal.domain;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;

/**
 * Assigns a collision-free hidden tunnel FQDN for Terminal/RemoteDesktop/PortForwarding, built
 * from {@link HiddenTunnelLabel#derive} with a numeric "-2", "-3", ... suffix on collision --
 * the same idea as the old NetworkServiceLabel-based subdomain generation this replaces, just
 * shared by both ServerController (add/edit) and BackupService (restore) instead of living
 * on the controller alone.
 */
@Component
public class HiddenTunnelFqdnAssigner {

	private final BoringProxyClient boringProxyClient;

	public HiddenTunnelFqdnAssigner(BoringProxyClient boringProxyClient) {
		this.boringProxyClient = boringProxyClient;
	}

	/**
	 * excludeFqdns is this Application's own currently-live hidden tunnel FQDNs, so re-saving
	 * unchanged (or renaming a sibling protocol) never makes an Application collide with itself.
	 */
	public String assign(String appFqdn, String suffix, String targetDomain, Set<String> excludeFqdns) {
		String base = HiddenTunnelLabel.derive(appFqdn, suffix);
		Map<String, TunnelDto> existing = boringProxyClient.listTunnels();
		String candidate = base;
		int collisionSuffix = 2;
		while (true) {
			String fqdn = candidate + "." + targetDomain;
			boolean taken = existing.keySet().stream()
					.anyMatch(d -> d.equalsIgnoreCase(fqdn) && excludeFqdns.stream().noneMatch(d::equalsIgnoreCase));
			if (!taken) {
				return fqdn;
			}
			candidate = base + "-" + collisionSuffix++;
		}
	}
}
