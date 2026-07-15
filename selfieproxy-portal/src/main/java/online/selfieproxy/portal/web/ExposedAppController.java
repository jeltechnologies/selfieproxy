package online.selfieproxy.portal.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;
import online.selfieproxy.portal.domain.ExposedApp;
import online.selfieproxy.portal.domain.ExposedAppStore;
import online.selfieproxy.portal.domain.ExposedAppType;
import online.selfieproxy.portal.domain.Protocol;
import online.selfieproxy.portal.domain.TunnelMapper;
import online.selfieproxy.portal.session.PortalSession;
import online.selfieproxy.portal.session.PortalSessions;

import jakarta.servlet.http.HttpServletRequest;

@Controller
public class ExposedAppController {

	/** Well-known/system port range (SSH, HTTPS, ...) that must never be exposed as a Network Service. */
	private static final int RESERVED_PORT_MAX = 1023;

	private final BoringProxyClient boringProxyClient;
	private final TunnelMapper tunnelMapper;
	private final BoringProxyProperties properties;
	private final ExposedAppStore exposedAppStore;
	private final ThisServerAgentProperties thisServerAgentProperties;

	public ExposedAppController(BoringProxyClient boringProxyClient, TunnelMapper tunnelMapper,
			BoringProxyProperties properties, ExposedAppStore exposedAppStore,
			ThisServerAgentProperties thisServerAgentProperties) {
		this.boringProxyClient = boringProxyClient;
		this.tunnelMapper = tunnelMapper;
		this.properties = properties;
		this.exposedAppStore = exposedAppStore;
		this.thisServerAgentProperties = thisServerAgentProperties;
	}

	@GetMapping("/apps/new")
	public String newApp(Model model) {
		List<String> homelabs = homelabs();
		ExposedApp app = new ExposedApp("", null, homelabs.stream().findFirst().orElse(null),
				ExposedAppType.WEB_APPLICATION, Protocol.HTTPS, "127.0.0.1", 443, null, null, true);
		model.addAttribute("app", app);
		model.addAttribute("isNew", true);
		model.addAttribute("domain", properties.domain());
		model.addAttribute("homelabs", homelabs);
		return "edit-app";
	}

	@GetMapping("/apps/{subdomain}/edit")
	public String editApp(@PathVariable String subdomain, Model model) {
		TunnelDto tunnel = boringProxyClient.getTunnel(currentFqdn(subdomain));
		model.addAttribute("app", exposedAppStore.reconcile(tunnelMapper.toExposedApp(tunnel)));
		model.addAttribute("isNew", false);
		model.addAttribute("domain", properties.domain());
		model.addAttribute("homelabs", homelabs());
		model.addAttribute("certPending", tunnel.certPending());
		return "edit-app";
	}

	@PostMapping("/apps")
	public String create(@ModelAttribute ExposedAppForm form, HttpServletRequest request, Model model) {
		PortalSession session = PortalSessions.get(request.getSession(false));
		ExposedApp app = toExposedApp(form);

		List<String> errors = validate(app, session, null);
		if (!errors.isEmpty()) {
			model.addAttribute("app", app);
			model.addAttribute("isNew", true);
			model.addAttribute("errors", errors);
			model.addAttribute("domain", properties.domain());
			model.addAttribute("homelabs", homelabs());
			return "edit-app";
		}

		boringProxyClient.createTunnel(tunnelMapper.toCreateTunnelRequest(app, session.owner()));
		exposedAppStore.save(app);
		return "redirect:/apps";
	}

	@PostMapping("/apps/{subdomain}")
	public String update(@PathVariable String subdomain, @ModelAttribute ExposedAppForm form,
			HttpServletRequest request, Model model) throws InterruptedException {
		PortalSession session = PortalSessions.get(request.getSession(false));
		ExposedApp app = toExposedApp(form);

		List<String> errors = validate(app, session, subdomain);
		if (!errors.isEmpty()) {
			model.addAttribute("app", app);
			model.addAttribute("isNew", false);
			model.addAttribute("errors", errors);
			model.addAttribute("domain", properties.domain());
			model.addAttribute("homelabs", homelabs());
			return "edit-app";
		}

		boringProxyClient.deleteTunnel(currentFqdn(subdomain));
		Thread.sleep(2000);
		boringProxyClient.createTunnel(tunnelMapper.toCreateTunnelRequest(app, session.owner()));
		if (!subdomain.equals(app.subdomain())) {
			exposedAppStore.delete(subdomain);
		}
		exposedAppStore.save(app);
		return "redirect:/apps";
	}

	@PostMapping("/apps/{subdomain}/delete")
	public String delete(@PathVariable String subdomain) {
		boringProxyClient.deleteTunnel(currentFqdn(subdomain));
		exposedAppStore.delete(subdomain);
		return "redirect:/apps";
	}

	/** Every ordinary Homelab except "This Server" -- that one is reserved for the Local Websites feature, not user-selectable here. */
	private List<String> homelabs() {
		return boringProxyClient.listAgents().keySet().stream()
				.filter(name -> !name.equals(thisServerAgentProperties.agentName()))
				.sorted()
				.toList();
	}

	/** The tunnel domain for subdomain as it exists right now (mode-aware via the stored record), or the default subdomain.DOMAIN composition if we have no record for it yet. */
	private String currentFqdn(String subdomain) {
		ExposedApp stored = exposedAppStore.find(subdomain);
		return stored != null ? tunnelMapper.fqdn(stored) : properties.fqdn(subdomain);
	}

	private ExposedApp toExposedApp(ExposedAppForm form) {
		boolean networkService = form.type() == ExposedAppType.NETWORK_SERVICE;
		String subdomain = networkService && (form.subdomain() == null || form.subdomain().isBlank())
				? generateUniqueSubdomain()
				: form.subdomain() == null ? null : form.subdomain().trim().toLowerCase();
		String name = networkService && form.name() != null && !form.name().isBlank() ? form.name().trim() : null;
		return new ExposedApp(subdomain, name, form.homelabName(), form.type(),
				networkService ? null : form.protocol(),
				form.host(), form.port() != null ? form.port() : 0, form.exposedPort(), form.tlsMode(),
				!networkService && Boolean.TRUE.equals(form.ssoProtected()));
	}

	/** Random internal subdomain for a Network Service, retried until it doesn't collide with an existing tunnel. */
	private String generateUniqueSubdomain() {
		Map<String, TunnelDto> existing = boringProxyClient.listTunnels();
		while (true) {
			String candidate = "svc-" + UUID.randomUUID().toString().substring(0, 8);
			String fqdn = properties.fqdn(candidate);
			if (existing.keySet().stream().noneMatch(domain -> domain.equalsIgnoreCase(fqdn))) {
				return candidate;
			}
		}
	}

	/** originalSubdomain is null when adding, and the subdomain being edited when updating (excluded from the collision check). */
	private List<String> validate(ExposedApp app, PortalSession session, String originalSubdomain) {
		List<String> errors = new ArrayList<>();

		if (app.subdomain() == null || app.subdomain().isBlank()) {
			errors.add("Subdomain is required.");
			return errors;
		}
		if (app.subdomain().contains(".")) {
			errors.add("Subdomain cannot contain a dot (\".\").");
		}
		if (app.subdomain().equalsIgnoreCase(properties.adminSubdomain())) {
			errors.add("\"" + app.subdomain() + "\" is reserved for the BoringProxy admin portal itself.");
		}
		if (app.subdomain().equalsIgnoreCase(properties.portalSubdomain())) {
			errors.add("\"" + app.subdomain() + "\" is reserved for the Selfie Proxy admin portal itself.");
		}
		if (app.subdomain().equalsIgnoreCase(properties.authSubdomain())) {
			errors.add("\"" + app.subdomain() + "\" is reserved for Selfie Proxy's bundled identity provider itself.");
		}

		String fqdn = tunnelMapper.fqdn(app);
		Map<String, TunnelDto> existing = boringProxyClient.listTunnels();
		boolean taken = existing.keySet().stream()
				.anyMatch(domain -> domain.equalsIgnoreCase(fqdn)
						&& (originalSubdomain == null || !domain.equalsIgnoreCase(currentFqdn(originalSubdomain))));
		if (taken) {
			errors.add("Subdomain \"" + app.subdomain() + "\" is already in use.");
		}

		if (app.ssoProtected() && !app.canProtectWithSso()) {
			errors.add("SSO protection requires Web Application, HTTPS, and the recommended End-to-end encrypted option.");
		}

		if (app.isNetworkService() && (app.name() == null || app.name().isBlank())) {
			errors.add("Name is required for a network service.");
		}

		if (app.isNetworkService() && app.exposedPort() == null) {
			errors.add("Exposed port to the internet is required for a network service.");
		} else if (app.isNetworkService()) {
			if (app.exposedPort() <= RESERVED_PORT_MAX) {
				errors.add("Port " + app.exposedPort() + " is reserved for system services and cannot be exposed.");
			} else {
				boolean portTaken = existing.entrySet().stream()
						.anyMatch(e -> e.getValue().allowExternalTcp()
								&& "passthrough".equals(e.getValue().tlsTermination())
								&& e.getValue().tunnelPort() == app.exposedPort()
								&& (originalSubdomain == null || !e.getKey().equals(properties.fqdn(originalSubdomain))));
				if (portTaken) {
					errors.add("Port " + app.exposedPort() + " is already exposed by another application.");
				}
			}
		}

		return errors;
	}
}
