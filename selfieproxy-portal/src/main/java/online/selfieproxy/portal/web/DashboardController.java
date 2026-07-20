package online.selfieproxy.portal.web;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.ThisServerAgentProperties;
import online.selfieproxy.portal.domain.ExposedApp;
import online.selfieproxy.portal.domain.ExposedAppStore;
import online.selfieproxy.portal.domain.TunnelMapper;

@Controller
public class DashboardController {

	private final BoringProxyClient boringProxyClient;
	private final TunnelMapper tunnelMapper;
	private final ExposedAppStore exposedAppStore;
	private final ThisServerAgentProperties thisServerAgentProperties;

	public DashboardController(BoringProxyClient boringProxyClient, TunnelMapper tunnelMapper,
			ExposedAppStore exposedAppStore, ThisServerAgentProperties thisServerAgentProperties) {
		this.boringProxyClient = boringProxyClient;
		this.tunnelMapper = tunnelMapper;
		this.exposedAppStore = exposedAppStore;
		this.thisServerAgentProperties = thisServerAgentProperties;
	}

	@GetMapping("/apps")
	public String dashboard(Model model) {
		List<String> homelabs = boringProxyClient.listAgents().keySet().stream()
				.filter(name -> !name.equals(thisServerAgentProperties.agentName()))
				.sorted()
				.toList();

		Map<String, TunnelDto> tunnels = boringProxyClient.listTunnels();

		// Renaming/removing a homelab has no effect on the tunnels that
		// already point at it (boringproxy has no cascade), and we deliberately
		// don't touch or hide that config -- it's still fully functional data,
		// just orphaned from a homelab that no longer exists. Surfaced
		// to the user instead via hasOrphanedApps / the per-row warning icon.
		// This Server's own tunnels are excluded entirely -- they belong to the
		// separate Local Websites feature (see LocalWebsiteController) and must
		// never get auto-captured back into ExposedAppStore/this dashboard.
		List<ExposedApp> exposedApps = tunnels.values().stream()
				.filter(tunnel -> !thisServerAgentProperties.agentName().equals(tunnel.agentName()))
				.map(tunnelMapper::toExposedApp)
				.map(exposedAppStore::reconcile)
				.sorted(Comparator.comparing(ExposedApp::subdomain))
				.toList();

		boolean hasOrphanedApps = exposedApps.stream()
				.anyMatch(app -> !homelabs.contains(app.homelabName()));

		// Domains still waiting on a Let's Encrypt certificate (e.g. after hitting a rate limit) --
		// boringproxy serves those over a temporary self-signed certificate in the meantime and
		// keeps retrying in the background, see selfieproxy-reverseproxy's TunnelManager. Scoped to
		// exposedApps' own tunnels (This Server/Local Websites excluded, same filter as above) --
		// otherwise a Local Website's own pending cert would incorrectly trigger this page's
		// "applications" warning for a completely separate feature.
		Map<String, Boolean> certPendingByDomain = tunnels.values().stream()
				.filter(tunnel -> !thisServerAgentProperties.agentName().equals(tunnel.agentName()))
				.collect(Collectors.toMap(TunnelDto::domain, TunnelDto::certPending));
		boolean hasPendingCerts = certPendingByDomain.values().stream().anyMatch(Boolean::booleanValue);

		model.addAttribute("homelabs", homelabs);
		model.addAttribute("exposedApps", exposedApps);
		model.addAttribute("hasOrphanedApps", hasOrphanedApps);
		model.addAttribute("certPendingByDomain", certPendingByDomain);
		model.addAttribute("hasPendingCerts", hasPendingCerts);
		model.addAttribute("tunnelMapper", tunnelMapper);
		return "dashboard";
	}
}
