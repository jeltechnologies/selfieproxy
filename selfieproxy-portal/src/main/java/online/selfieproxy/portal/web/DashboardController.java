package online.selfieproxy.portal.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import online.selfieproxy.portal.boringproxy.AgentStatusService;
import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;
import online.selfieproxy.portal.domain.DomainFilterPreferenceStore;
import online.selfieproxy.portal.domain.DomainService;
import online.selfieproxy.portal.domain.GatewayPortsChecker;
import online.selfieproxy.portal.domain.Server;
import online.selfieproxy.portal.domain.ServerStore;

@Controller
public class DashboardController {

	private final BoringProxyClient boringProxyClient;
	private final ServerStore serverStore;
	private final ThisServerAgentProperties thisServerAgentProperties;
	private final DomainService domainService;
	private final AgentStatusService agentStatusService;
	private final BoringProxyProperties properties;
	private final DomainFilterPreferenceStore domainFilterPreferenceStore;
	private final GatewayPortsChecker gatewayPortsChecker;

	public DashboardController(BoringProxyClient boringProxyClient, ServerStore serverStore,
			ThisServerAgentProperties thisServerAgentProperties, DomainService domainService,
			AgentStatusService agentStatusService, BoringProxyProperties properties,
			DomainFilterPreferenceStore domainFilterPreferenceStore, GatewayPortsChecker gatewayPortsChecker) {
		this.boringProxyClient = boringProxyClient;
		this.serverStore = serverStore;
		this.thisServerAgentProperties = thisServerAgentProperties;
		this.domainService = domainService;
		this.agentStatusService = agentStatusService;
		this.properties = properties;
		this.domainFilterPreferenceStore = domainFilterPreferenceStore;
		this.gatewayPortsChecker = gatewayPortsChecker;
	}

	@GetMapping("/servers")
	public String dashboard(Model model) {
		List<String> homelabs = boringProxyClient.listAgents().keySet().stream()
				.filter(name -> !name.equals(thisServerAgentProperties.agentName()))
				.sorted()
				.toList();

		List<Server> servers = loadServers();

		boolean hasOrphanedServers = servers.stream()
				.anyMatch(server -> !homelabs.contains(server.homelabName()));

		// Single source of truth for reachability/cert-pending, shared with the /servers/status
		// endpoint dashboard.js polls to refresh the Status column and Connect buttons without a
		// full page reload -- see the Homelabs page's own agents.js/AgentController.status().
		List<ServerStatusItem> statusItems = loadServerStatusItems(servers);
		Map<String, String> serverStatusMessage = statusItems.stream()
				.filter(ServerStatusItem::offline)
				.collect(Collectors.toMap(ServerStatusItem::fqdn, ServerStatusItem::statusMessage));
		Map<String, Boolean> certPendingByDomain = statusItems.stream()
				.collect(Collectors.toMap(ServerStatusItem::fqdn, ServerStatusItem::certPending));
		boolean hasPendingCerts = certPendingByDomain.values().stream().anyMatch(Boolean::booleanValue);

		model.addAttribute("homelabs", homelabs);
		model.addAttribute("servers", servers);
		model.addAttribute("hasOrphanedServers", hasOrphanedServers);
		model.addAttribute("certPendingByDomain", certPendingByDomain);
		model.addAttribute("hasPendingCerts", hasPendingCerts);
		model.addAttribute("serverStatusMessage", serverStatusMessage);
		model.addAttribute("domainService", domainService);
		model.addAttribute("domains", domainService.allDomains());
		model.addAttribute("consoleDomain", properties.consoleDomain());
		model.addAttribute("selectedDomainFilter", domainFilterPreferenceStore.load().serversDomain());
		model.addAttribute("selectedHomelabFilter", domainFilterPreferenceStore.load().serversHomelab());
		return "dashboard";
	}

	/** Polled every 2s by dashboard.js to refresh the Status column and Connect buttons without a full page reload. */
	@GetMapping(value = "/servers/status", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public List<ServerStatusItem> status() {
		return loadServerStatusItems(loadServers());
	}

	/** Fired by sortable-table.js on every domain-filter change so the choice survives navigation and a server reboot -- see DomainFilterPreferenceStore. */
	@PostMapping("/servers/domain-filter")
	public ResponseEntity<Void> saveDomainFilter(@RequestParam(value = "domain", required = false) String domain) {
		domainFilterPreferenceStore.saveServersDomain(domain == null || domain.isBlank() ? null : domain);
		return ResponseEntity.noContent().build();
	}

	/** Fired by sortable-table.js on every homelab-filter change so the choice survives navigation and a server reboot -- see DomainFilterPreferenceStore. */
	@PostMapping("/servers/homelab-filter")
	public ResponseEntity<Void> saveHomelabFilter(@RequestParam(value = "homelab", required = false) String homelab) {
		domainFilterPreferenceStore.saveServersHomelab(homelab == null || homelab.isBlank() ? null : homelab);
		return ResponseEntity.noContent().build();
	}

	/**
	 * ServerStore is the sole source of truth for the Server list now -- unlike the old
	 * single-protocol model, a hidden Terminal/RemoteDesktop/PortForwarding tunnel's generated FQDN
	 * can't be reverse-parsed back to "which Server, which protocol", so the list can no
	 * longer be derived by listing live boringproxy tunnels. A tunnel created outside Selfie Proxy
	 * (eg. directly via boringproxy's own legacy API) no longer auto-appears here as a result.
	 */
	private List<Server> loadServers() {
		return serverStore.values().stream()
				.sorted(Comparator.comparing(server -> server.subdomain() == null ? "" : server.subdomain()))
				.toList();
	}

	/**
	 * Whether each server is actually reachable right now: its homelab connected, (for a Web-enabled
	 * server) its domain's DNS actually pointing at this server, and (for a Port-Forwarding-enabled
	 * server) the host's sshd actually configured to let that tunnel's remote-forward bind
	 * non-loopback (see GatewayPortsChecker) -- offline=false means fully OK -- green
	 * dot, no column text; true means red, with the specific problem(s) named rather than one
	 * generic label -- see the Status column on dashboard.html, plus whether Web's certificate is
	 * still a temporary self-signed one (see selfieproxy-reverseproxy's TunnelManager) -- only Web
	 * ever gets a managed cert, so this is skipped entirely for an server with Web disabled.
	 */
	private List<ServerStatusItem> loadServerStatusItems(List<Server> servers) {
		Map<String, Boolean> onlineByAgent = agentStatusService.onlineByAgentName();
		String serverIp = domainService.serverIp();
		Map<String, TunnelDto> tunnels = boringProxyClient.listTunnels();

		List<ServerStatusItem> items = new ArrayList<>();
		for (Server server : servers) {
			List<String> issues = new ArrayList<>();
			if (!onlineByAgent.getOrDefault(server.homelabName(), false)) {
				issues.add("Homelab " + server.homelabName() + " is disconnected.");
			}
			if (server.hasWeb() && domainService.hasDnsMismatch(server.fqdn(), serverIp)) {
				issues.add("Domain not correctly configured.");
			}
			if (server.hasPortForwarding() && !gatewayPortsChecker.isConfigured()) {
				issues.add("GatewayPorts is not configured in sshd_config -- Port Forwarding will not work.");
			}
			boolean offline = !issues.isEmpty();
			TunnelDto webTunnel = server.hasWeb() ? tunnels.get(server.fqdn()) : null;
			boolean certPending = webTunnel != null && webTunnel.certPending();
			items.add(new ServerStatusItem(server.fqdn(), offline, offline ? String.join(" ", issues) : null, certPending));
		}
		return items;
	}
}
