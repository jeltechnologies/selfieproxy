package online.selfieproxy.portal.web;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import online.selfieproxy.portal.boringproxy.AgentStatusService;
import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;
import online.selfieproxy.portal.domain.RemoteConsole;
import online.selfieproxy.portal.domain.RemoteConsoleAuthMode;
import online.selfieproxy.portal.domain.RemoteConsoleProtocol;
import online.selfieproxy.portal.domain.RemoteConsoleStore;
import online.selfieproxy.portal.security.RemoteConsoleCredentialCipher;
import online.selfieproxy.portal.session.PortalSession;
import online.selfieproxy.portal.session.PortalSessions;

import jakarta.servlet.http.HttpServletRequest;

/**
 * CRUD for Remote Consoles (browser SSH/RDP/VNC) -- config lives here, same
 * as every other Homelab-facing concept, even though the live session itself
 * is served by the separate selfieproxy-remote-console service (see its own
 * CLAUDE.md and root CLAUDE.md's "Remote consoles" section). No TunnelMapper
 * reuse here -- the tunnel shape (always passthrough, always
 * allowExternalTcp=false, no TLS/SSO concerns at all) is different enough
 * from an Exposed App to keep this local to the controller.
 */
@Controller
public class RemoteConsoleController {

	private final BoringProxyClient boringProxyClient;
	private final BoringProxyProperties properties;
	private final RemoteConsoleStore remoteConsoleStore;
	private final RemoteConsoleCredentialCipher cipher;
	private final ThisServerAgentProperties thisServerAgentProperties;
	private final AgentStatusService agentStatusService;

	public RemoteConsoleController(BoringProxyClient boringProxyClient, BoringProxyProperties properties,
			RemoteConsoleStore remoteConsoleStore, RemoteConsoleCredentialCipher cipher,
			ThisServerAgentProperties thisServerAgentProperties, AgentStatusService agentStatusService) {
		this.boringProxyClient = boringProxyClient;
		this.properties = properties;
		this.remoteConsoleStore = remoteConsoleStore;
		this.cipher = cipher;
		this.thisServerAgentProperties = thisServerAgentProperties;
		this.agentStatusService = agentStatusService;
	}

	@GetMapping("/remote-consoles")
	public String list(Model model) {
		model.addAttribute("consoles", remoteConsoleStore.findAll());
		model.addAttribute("consoleDomain", properties.consoleDomain());
		model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
		return "remote-consoles";
	}

	@GetMapping("/remote-consoles/new")
	public String newConsole(Model model) {
		List<String> homelabs = homelabs();
		RemoteConsole console = new RemoteConsole(null, "", homelabs.stream().findFirst().orElse(null),
				RemoteConsoleProtocol.SSH, "", RemoteConsoleProtocol.SSH.defaultPort(), "",
				RemoteConsoleAuthMode.PASSWORD, null, true, null, 0);
		model.addAttribute("console", console);
		model.addAttribute("isNew", true);
		model.addAttribute("homelabs", homelabs);
		model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
		return "edit-remote-console";
	}

	@GetMapping("/remote-consoles/{id}/edit")
	public String editConsole(@PathVariable String id, Model model) {
		RemoteConsole console = remoteConsoleStore.find(id);
		model.addAttribute("console", console);
		model.addAttribute("isNew", false);
		model.addAttribute("homelabs", homelabs());
		model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
		return "edit-remote-console";
	}

	@PostMapping("/remote-consoles")
	public String create(@ModelAttribute RemoteConsoleForm form, HttpServletRequest request, Model model) {
		PortalSession session = PortalSessions.get(request.getSession(false));

		List<String> errors = validate(form);
		if (!errors.isEmpty()) {
			return rerenderNew(form, errors, model);
		}

		String id = UUID.randomUUID().toString();
		String internalFqdn = generateUniqueInternalFqdn();

		TunnelDto tunnel = boringProxyClient.createTunnel(new CreateTunnelRequestDto(
				internalFqdn, session.owner(), form.homelabName(), form.port(), form.host(),
				null, false, null, null, null, "passthrough", null, null, null));

		RemoteConsole console = new RemoteConsole(id, form.name().trim(), form.homelabName(), form.protocol(),
				form.host(), form.port(), blankToNull(form.username()), effectiveAuthMode(form),
				cipher.encrypt(form.secret()), Boolean.TRUE.equals(form.ignoreCertificate()), internalFqdn,
				tunnel.tunnelPort());
		remoteConsoleStore.save(console);
		return "redirect:/remote-consoles";
	}

	@PostMapping("/remote-consoles/{id}")
	public String update(@PathVariable String id, @ModelAttribute RemoteConsoleForm form,
			HttpServletRequest request, Model model) throws InterruptedException {
		PortalSession session = PortalSessions.get(request.getSession(false));
		RemoteConsole existing = remoteConsoleStore.find(id);

		List<String> errors = validate(form);
		if (!errors.isEmpty()) {
			model.addAttribute("console", existing);
			model.addAttribute("isNew", false);
			model.addAttribute("errors", errors);
			model.addAttribute("homelabs", homelabs());
			model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
			return "edit-remote-console";
		}

		boringProxyClient.deleteTunnel(existing.internalFqdn());
		Thread.sleep(2000);
		TunnelDto tunnel = boringProxyClient.createTunnel(new CreateTunnelRequestDto(
				existing.internalFqdn(), session.owner(), form.homelabName(), form.port(), form.host(),
				null, false, null, null, null, "passthrough", null, null, null));

		String encryptedSecret = form.secret() == null || form.secret().isBlank()
				? existing.encryptedSecret()
				: cipher.encrypt(form.secret());

		RemoteConsole console = new RemoteConsole(id, form.name().trim(), form.homelabName(), form.protocol(),
				form.host(), form.port(), blankToNull(form.username()), effectiveAuthMode(form),
				encryptedSecret, Boolean.TRUE.equals(form.ignoreCertificate()), existing.internalFqdn(),
				tunnel.tunnelPort());
		remoteConsoleStore.save(console);
		return "redirect:/remote-consoles";
	}

	@PostMapping("/remote-consoles/{id}/delete")
	public String delete(@PathVariable String id) {
		RemoteConsole existing = remoteConsoleStore.find(id);
		if (existing != null) {
			boringProxyClient.deleteTunnel(existing.internalFqdn());
			remoteConsoleStore.delete(id);
		}
		return "redirect:/remote-consoles";
	}

	/** Every ordinary Homelab except "This Server" -- same filter as ExposedAppController.homelabs(). */
	private List<String> homelabs() {
		return boringProxyClient.listAgents().keySet().stream()
				.filter(name -> !name.equals(thisServerAgentProperties.agentName()))
				.sorted()
				.toList();
	}

	private RemoteConsoleAuthMode effectiveAuthMode(RemoteConsoleForm form) {
		return form.protocol() == RemoteConsoleProtocol.SSH && form.authMode() != null
				? form.authMode()
				: RemoteConsoleAuthMode.PASSWORD;
	}

	private String generateUniqueInternalFqdn() {
		java.util.Map<String, TunnelDto> existing = boringProxyClient.listTunnels();
		while (true) {
			String candidate = "rc-" + UUID.randomUUID().toString().substring(0, 8) + "." + properties.primaryDomain();
			if (existing.keySet().stream().noneMatch(d -> d.equalsIgnoreCase(candidate))) {
				return candidate;
			}
		}
	}

	private String rerenderNew(RemoteConsoleForm form, List<String> errors, Model model) {
		RemoteConsole console = new RemoteConsole(null, form.name(), form.homelabName(),
				form.protocol() != null ? form.protocol() : RemoteConsoleProtocol.SSH, form.host(),
				form.port() != null ? form.port() : 0, form.username(), effectiveAuthMode(form), null,
				Boolean.TRUE.equals(form.ignoreCertificate()), null, 0);
		model.addAttribute("console", console);
		model.addAttribute("isNew", true);
		model.addAttribute("errors", errors);
		model.addAttribute("homelabs", homelabs());
		model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
		return "edit-remote-console";
	}

	private List<String> validate(RemoteConsoleForm form) {
		List<String> errors = new ArrayList<>();

		if (form.name() == null || form.name().isBlank()) {
			errors.add("Name is required.");
		}
		if (form.homelabName() == null || form.homelabName().isBlank()) {
			errors.add("Homelab is required.");
		}
		if (form.protocol() == null) {
			errors.add("Protocol is required.");
		}
		if (form.host() == null || form.host().isBlank()) {
			errors.add("Host or IP address is required.");
		}
		if (form.port() == null || form.port() < 1 || form.port() > 65535) {
			errors.add("Port must be between 1 and 65535.");
		}
		if (form.protocol() == RemoteConsoleProtocol.SSH && form.authMode() == RemoteConsoleAuthMode.PRIVATE_KEY
				&& (form.secret() == null || form.secret().isBlank())) {
			errors.add("A private key is required when using key-based authentication.");
		}

		return errors;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
