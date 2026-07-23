package online.selfieproxy.portal.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import online.selfieproxy.portal.boringproxy.AgentStatusService;
import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;
import online.selfieproxy.portal.domain.DomainService;
import online.selfieproxy.portal.domain.ExposedApp;
import online.selfieproxy.portal.domain.ExposedAppStore;
import online.selfieproxy.portal.domain.TunnelMapper;

@Controller
public class DashboardController {

	private final BoringProxyClient boringProxyClient;
	private final TunnelMapper tunnelMapper;
	private final ExposedAppStore exposedAppStore;
	private final ThisServerAgentProperties thisServerAgentProperties;
	private final DomainService domainService;
	private final AgentStatusService agentStatusService;
	private final BoringProxyProperties properties;

	public DashboardController(BoringProxyClient boringProxyClient, TunnelMapper tunnelMapper,
			ExposedAppStore exposedAppStore, ThisServerAgentProperties thisServerAgentProperties,
			DomainService domainService, AgentStatusService agentStatusService, BoringProxyProperties properties) {
		this.boringProxyClient = boringProxyClient;
		this.tunnelMapper = tunnelMapper;
		this.exposedAppStore = exposedAppStore;
		this.thisServerAgentProperties = thisServerAgentProperties;
		this.domainService = domainService;
		this.agentStatusService = agentStatusService;
		this.properties = properties;
	}

	@GetMapping("/apps")
	public String dashboard(Model model) {
		List<String> homelabs = boringProxyClient.listAgents().keySet().stream()
				.filter(name -> !name.equals(thisServerAgentProperties.agentName()))
				.sorted()
				.toList();

		Map<String, TunnelDto> tunnels = boringProxyClient.listTunnels();
		List<ExposedApp> exposedApps = loadExposedApps(tunnels).stream()
				.sorted(Comparator.comparing(app -> app.subdomain() == null ? "" : app.subdomain()))
				.toList();

		boolean hasOrphanedApps = exposedApps.stream()
				.anyMatch(app -> !homelabs.contains(app.homelabName()));

		// Single source of truth for reachability/cert-pending, shared with the /apps/status
		// endpoint dashboard.js polls to refresh the Status column and Connect buttons without a
		// full page reload -- see the Homelabs page's own agents.js/AgentController.status().
		List<AppStatusItem> statusItems = loadAppStatusItems(exposedApps, tunnels);
		Map<String, String> appStatusMessage = statusItems.stream()
				.filter(AppStatusItem::offline)
				.collect(Collectors.toMap(AppStatusItem::fqdn, AppStatusItem::statusMessage));
		Map<String, Boolean> certPendingByDomain = statusItems.stream()
				.collect(Collectors.toMap(AppStatusItem::fqdn, AppStatusItem::certPending));
		boolean hasPendingCerts = certPendingByDomain.values().stream().anyMatch(Boolean::booleanValue);

		model.addAttribute("homelabs", homelabs);
		model.addAttribute("exposedApps", exposedApps);
		model.addAttribute("hasOrphanedApps", hasOrphanedApps);
		model.addAttribute("certPendingByDomain", certPendingByDomain);
		model.addAttribute("hasPendingCerts", hasPendingCerts);
		model.addAttribute("appStatusMessage", appStatusMessage);
		model.addAttribute("tunnelMapper", tunnelMapper);
		model.addAttribute("domainService", domainService);
		model.addAttribute("domains", domainService.allDomains());
		model.addAttribute("consoleDomain", properties.consoleDomain());
		return "dashboard";
	}

	/** Polled every 2s by dashboard.js to refresh the Status column and Connect buttons without a full page reload. */
	@GetMapping(value = "/apps/status", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public List<AppStatusItem> status() {
		Map<String, TunnelDto> tunnels = boringProxyClient.listTunnels();
		return loadAppStatusItems(loadExposedApps(tunnels), tunnels);
	}

	// Renaming/removing a homelab has no effect on the tunnels that
	// already point at it (boringproxy has no cascade), and we deliberately
	// don't touch or hide that config -- it's still fully functional data,
	// just orphaned from a homelab that no longer exists. Surfaced
	// to the user instead via hasOrphanedApps / the per-row warning icon.
	// This Server's own tunnels are excluded entirely -- they belong to the
	// separate Local Websites feature (see LocalWebsiteController) and must
	// never get auto-captured back into ExposedAppStore/this dashboard.
	private List<ExposedApp> loadExposedApps(Map<String, TunnelDto> tunnels) {
		return tunnels.values().stream()
				.filter(tunnel -> !thisServerAgentProperties.agentName().equals(tunnel.agentName()))
				.map(tunnelMapper::toExposedApp)
				.map(exposedAppStore::reconcile)
				.toList();
	}

	/**
	 * Whether each app is actually reachable right now: its homelab connected and its domain's DNS
	 * actually pointing at this server (offline=false means fully OK -- green dot, no column text;
	 * true means red, with the specific problem(s) named rather than one generic label -- see the
	 * Status column on dashboard.html), plus whether its certificate is still a temporary
	 * self-signed one (see selfieproxy-reverseproxy's TunnelManager). tunnels is passed in rather
	 * than re-fetched so a caller that already has it (dashboard(), which also needs it for
	 * hasOrphanedApps context) doesn't hit boringproxy twice.
	 */
	private List<AppStatusItem> loadAppStatusItems(List<ExposedApp> exposedApps, Map<String, TunnelDto> tunnels) {
		Map<String, Boolean> onlineByAgent = agentStatusService.onlineByAgentName();
		String serverIp = domainService.serverIp();
		Map<String, Boolean> certPendingByDomain = tunnels.values().stream()
				.filter(tunnel -> !thisServerAgentProperties.agentName().equals(tunnel.agentName()))
				.collect(Collectors.toMap(TunnelDto::domain, TunnelDto::certPending));

		List<AppStatusItem> items = new ArrayList<>();
		for (ExposedApp app : exposedApps) {
			List<String> issues = new ArrayList<>();
			if (!onlineByAgent.getOrDefault(app.homelabName(), false)) {
				issues.add("Homelab " + app.homelabName() + " is disconnected.");
			}
			if (domainService.hasDnsMismatch(app.fqdn(), serverIp)) {
				issues.add("Domain not correctly configured.");
			}
			boolean offline = !issues.isEmpty();
			items.add(new AppStatusItem(app.fqdn(), offline, offline ? String.join(" ", issues) : null,
					certPendingByDomain.getOrDefault(app.fqdn(), false)));
		}
		return items;
	}
}
