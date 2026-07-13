package online.selfieproxy.portal.web;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.AgentStatusDto;
import online.selfieproxy.portal.boringproxy.dto.TokenDataDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.ThisServerAgentProperties;

@Controller
public class AgentController {

	/** Selfie Proxy has exactly one boringproxy user; see selfieproxy.md. */
	private static final String OWNER = "admin";

	/**
	 * An agent polls GET /api/tunnels every -poll-interval-ms (2000ms by
	 * default) and boringproxy records that as its last-seen heartbeat.
	 * Anything older than this is considered offline -- 2.5x the poll
	 * interval, enough to absorb one missed poll plus jitter/latency
	 * without flapping, while still surfacing a real disconnect quickly.
	 */
	private static final Duration ONLINE_THRESHOLD = Duration.ofSeconds(5);

	private final BoringProxyClient boringProxyClient;
	private final ThisServerAgentProperties thisServerAgentProperties;

	public AgentController(BoringProxyClient boringProxyClient, ThisServerAgentProperties thisServerAgentProperties) {
		this.boringProxyClient = boringProxyClient;
		this.thisServerAgentProperties = thisServerAgentProperties;
	}

	@GetMapping("/")
	public String list(Model model) {
		model.addAttribute("agents", loadAgentListItems());
		return "agents";
	}

	/** Polled every 2s by agents.js to refresh the connected/disconnected dot and app count without a full page reload. */
	@GetMapping(value = "/agents/status", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public List<AgentListItem> status() {
		return loadAgentListItems();
	}

	@GetMapping("/agents/new")
	public String newAgent(Model model) {
		model.addAttribute("agent", new AgentView("", null));
		model.addAttribute("isNew", true);
		return "edit-agent";
	}

	@GetMapping("/agents/{name}/edit")
	public String editAgent(@PathVariable String name, Model model) {
		if (isThisServer(name)) {
			return "redirect:/";
		}
		model.addAttribute("agent", new AgentView(name, secretFor(name)));
		model.addAttribute("isNew", false);
		return "edit-agent";
	}

	@PostMapping("/agents")
	public String create(@ModelAttribute AgentForm form, Model model) {
		List<String> errors = validateName(form.name(), null);
		if (!errors.isEmpty()) {
			model.addAttribute("agent", new AgentView(form.name(), null));
			model.addAttribute("isNew", true);
			model.addAttribute("errors", errors);
			return "edit-agent";
		}

		boringProxyClient.createAgent(OWNER, form.name());
		boringProxyClient.createToken(OWNER, form.name());
		return "redirect:/agents/" + form.name() + "/edit";
	}

	@PostMapping("/agents/{name}")
	public String rename(@PathVariable String name, @ModelAttribute AgentForm form, Model model) {
		if (isThisServer(name)) {
			return "redirect:/";
		}
		String newName = form.name();

		if (newName.equals(name)) {
			return "redirect:/";
		}

		List<String> errors = validateName(newName, name);
		if (!errors.isEmpty()) {
			model.addAttribute("agent", new AgentView(name, secretFor(name)));
			model.addAttribute("isNew", false);
			model.addAttribute("errors", errors);
			return "edit-agent";
		}

		// boringproxy has no rename primitive for the agent record itself -- re-create
		// it under the new name and remove the old one. Its secret is just a token
		// re-pointed at an agent name, though, so that part we retarget in place
		// instead of minting a fresh one -- renaming must never invalidate a secret
		// that's already configured on the homelab's .env. Every exposed app
		// under the old name must follow the rename too, or it'd be silently
		// orphaned (see the warning icon/banner on the Exposed applications page).
		boringProxyClient.createAgent(OWNER, newName);
		retargetTokensForAgent(name, newName);
		retargetTunnelsForAgent(name, newName);
		boringProxyClient.deleteAgent(OWNER, name);
		return "redirect:/";
	}

	@PostMapping("/agents/{name}/regenerate-secret")
	public String regenerateSecret(@PathVariable String name) {
		if (isThisServer(name)) {
			return "redirect:/";
		}
		deleteTokensForAgent(name);
		boringProxyClient.createToken(OWNER, name);
		return "redirect:/agents/" + name + "/edit";
	}

	@PostMapping("/agents/{name}/delete")
	public String delete(@PathVariable String name) {
		if (isThisServer(name)) {
			return "redirect:/";
		}
		deleteTokensForAgent(name);
		boringProxyClient.deleteAgent(OWNER, name);
		return "redirect:/";
	}

	/** "This Server" is an ordinary Agent under the hood, but it's reserved for the Local Websites feature, not a Homelab a user manages here. */
	private boolean isThisServer(String name) {
		return thisServerAgentProperties.agentName().equals(name);
	}

	private List<AgentListItem> loadAgentListItems() {
		Map<String, Long> appCountsByHomelab = boringProxyClient.listTunnels().values().stream()
				.collect(Collectors.groupingBy(TunnelDto::agentName, Collectors.counting()));

		List<AgentListItem> items = new ArrayList<>();
		for (Map.Entry<String, AgentStatusDto> entry : boringProxyClient.listAgents().entrySet()) {
			String name = entry.getKey();
			if (isThisServer(name)) {
				continue;
			}
			int appCount = appCountsByHomelab.getOrDefault(name, 0L).intValue();
			items.add(new AgentListItem(name, isOnline(entry.getValue()), appCount));
		}
		items.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		return items;
	}

	private boolean isOnline(AgentStatusDto status) {
		if (status == null || status.lastSeen() == null) {
			return false;
		}
		try {
			Instant lastSeen = Instant.parse(status.lastSeen());
			return lastSeen.isAfter(Instant.now().minus(ONLINE_THRESHOLD));
		} catch (DateTimeParseException e) {
			return false;
		}
	}

	private String secretFor(String name) {
		return secretFor(name, boringProxyClient.listTokens());
	}

	/** An agent's secret is recoverable at any time -- it's just its own agent-scoped boringproxy access token. */
	private String secretFor(String name, Map<String, TokenDataDto> tokens) {
		return tokens.entrySet().stream()
				.filter(e -> name.equals(e.getValue().agent()))
				.map(Map.Entry::getKey)
				.findFirst()
				.orElse(null);
	}

	private void deleteTokensForAgent(String name) {
		boringProxyClient.listTokens().entrySet().stream()
				.filter(e -> name.equals(e.getValue().agent()))
				.map(Map.Entry::getKey)
				.forEach(boringProxyClient::deleteToken);
	}

	private void retargetTokensForAgent(String oldName, String newName) {
		boringProxyClient.listTokens().entrySet().stream()
				.filter(e -> oldName.equals(e.getValue().agent()))
				.map(Map.Entry::getKey)
				.forEach(secret -> boringProxyClient.renameTokenAgent(secret, newName));
	}

	private void retargetTunnelsForAgent(String oldName, String newName) {
		boringProxyClient.listTunnels().values().stream()
				.filter(tunnel -> oldName.equals(tunnel.agentName()))
				.map(TunnelDto::domain)
				.forEach(domain -> boringProxyClient.renameTunnelAgent(domain, newName));
	}

	private List<String> validateName(String name, String originalName) {
		List<String> errors = new ArrayList<>();
		if (name == null || name.isBlank()) {
			errors.add("Homelab name is required.");
			return errors;
		}
		if (isThisServer(name)) {
			errors.add("\"" + name + "\" is reserved for Local Websites.");
			return errors;
		}

		boolean taken = boringProxyClient.listAgents().keySet().stream()
				.anyMatch(existing -> existing.equalsIgnoreCase(name)
						&& (originalName == null || !existing.equalsIgnoreCase(originalName)));
		if (taken) {
			errors.add("Homelab \"" + name + "\" already exists.");
		}

		return errors;
	}
}
