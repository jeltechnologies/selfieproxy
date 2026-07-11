package online.selfieproxy.portal.web;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.domain.ExposedApp;
import online.selfieproxy.portal.domain.ExposedAppStore;
import online.selfieproxy.portal.domain.TunnelMapper;

@Controller
public class DashboardController {

	private final BoringProxyClient boringProxyClient;
	private final TunnelMapper tunnelMapper;
	private final ExposedAppStore exposedAppStore;

	public DashboardController(BoringProxyClient boringProxyClient, TunnelMapper tunnelMapper,
			ExposedAppStore exposedAppStore) {
		this.boringProxyClient = boringProxyClient;
		this.tunnelMapper = tunnelMapper;
		this.exposedAppStore = exposedAppStore;
	}

	@GetMapping("/apps")
	public String dashboard(Model model) {
		List<String> localNetworks = boringProxyClient.listAgents().keySet().stream()
				.sorted()
				.toList();

		Map<String, TunnelDto> tunnels = boringProxyClient.listTunnels();

		// Renaming/removing a local network has no effect on the tunnels that
		// already point at it (boringproxy has no cascade), and we deliberately
		// don't touch or hide that config -- it's still fully functional data,
		// just orphaned from a local network that no longer exists. Surfaced
		// to the user instead via hasOrphanedApps / the per-row warning icon.
		List<ExposedApp> exposedApps = tunnels.values().stream()
				.map(tunnelMapper::toExposedApp)
				.map(exposedAppStore::reconcile)
				.sorted(Comparator.comparing(ExposedApp::subdomain))
				.toList();

		boolean hasOrphanedApps = exposedApps.stream()
				.anyMatch(app -> !localNetworks.contains(app.localNetworkName()));

		model.addAttribute("localNetworks", localNetworks);
		model.addAttribute("exposedApps", exposedApps);
		model.addAttribute("hasOrphanedApps", hasOrphanedApps);
		model.addAttribute("tunnelMapper", tunnelMapper);
		return "dashboard";
	}
}
