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

import online.selfieproxy.portal.boringproxy.AgentStatusService;
import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;
import online.selfieproxy.portal.domain.DnsLabelValidator;
import online.selfieproxy.portal.domain.DomainService;
import online.selfieproxy.portal.domain.ExposedApp;
import online.selfieproxy.portal.domain.ExposedAppStore;
import online.selfieproxy.portal.domain.ExposedAppType;
import online.selfieproxy.portal.domain.NetworkServiceMode;
import online.selfieproxy.portal.domain.Protocol;
import online.selfieproxy.portal.domain.TunnelMapper;
import online.selfieproxy.portal.security.NetworkServiceCredentialCipher;
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
	private final NetworkServiceCredentialCipher cipher;
	private final ThisServerAgentProperties thisServerAgentProperties;
	private final DomainService domainService;
	private final AgentStatusService agentStatusService;

	public ExposedAppController(BoringProxyClient boringProxyClient, TunnelMapper tunnelMapper,
			BoringProxyProperties properties, ExposedAppStore exposedAppStore, NetworkServiceCredentialCipher cipher,
			ThisServerAgentProperties thisServerAgentProperties, DomainService domainService,
			AgentStatusService agentStatusService) {
		this.boringProxyClient = boringProxyClient;
		this.tunnelMapper = tunnelMapper;
		this.properties = properties;
		this.exposedAppStore = exposedAppStore;
		this.cipher = cipher;
		this.thisServerAgentProperties = thisServerAgentProperties;
		this.domainService = domainService;
		this.agentStatusService = agentStatusService;
	}

	@GetMapping("/apps/new")
	public String newApp(Model model) {
		List<String> homelabs = homelabs();
		ExposedApp app = new ExposedApp("", null, homelabs.stream().findFirst().orElse(null),
				ExposedAppType.WEB_APPLICATION, Protocol.HTTPS, "127.0.0.1", 443, null, null, true,
				properties.primaryDomain(), null, null, null, false);
		model.addAttribute("app", app);
		model.addAttribute("isNew", true);
		model.addAttribute("domains", domainService.allDomains());
		model.addAttribute("homelabs", homelabs);
		model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
		return "edit-app";
	}

	@GetMapping("/apps/{fqdn}/edit")
	public String editApp(@PathVariable String fqdn, Model model) {
		TunnelDto tunnel = boringProxyClient.getTunnel(fqdn);
		model.addAttribute("app", exposedAppStore.reconcile(tunnelMapper.toExposedApp(tunnel)));
		model.addAttribute("isNew", false);
		model.addAttribute("domains", domainService.allDomains());
		model.addAttribute("homelabs", homelabs());
		model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
		model.addAttribute("certPending", tunnel.certPending());
		return "edit-app";
	}

	@PostMapping("/apps")
	public String create(@ModelAttribute ExposedAppForm form, HttpServletRequest request, Model model) {
		PortalSession session = PortalSessions.get(request.getSession(false));
		ExposedApp app = toExposedApp(form, null);

		List<String> errors = validate(app, session, null);
		if (!errors.isEmpty()) {
			model.addAttribute("app", app);
			model.addAttribute("isNew", true);
			model.addAttribute("errors", errors);
			model.addAttribute("domains", domainService.allDomains());
			model.addAttribute("homelabs", homelabs());
			model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
			return "edit-app";
		}

		TunnelDto tunnel = boringProxyClient.createTunnel(tunnelMapper.toCreateTunnelRequest(app, session.owner()));
		// SSH/RDP/VNC mode submits exposedPort null (boringproxy auto-assigns it) -- capture the
		// real assigned port from the response, or selfieproxy-remote-console ends up dialing
		// port 0 (see ExposedApp.withExposedPort).
		if (app.isRemoteAccessMode()) {
			app = app.withExposedPort(tunnel.tunnelPort());
		}
		exposedAppStore.save(app);
		return "redirect:/apps";
	}

	@PostMapping("/apps/{fqdn}")
	public String update(@PathVariable String fqdn, @ModelAttribute ExposedAppForm form,
			HttpServletRequest request, Model model) throws InterruptedException {
		PortalSession session = PortalSessions.get(request.getSession(false));
		ExposedApp existing = exposedAppStore.find(fqdn);
		ExposedApp app = toExposedApp(form, existing);

		List<String> errors = validate(app, session, fqdn);
		if (!errors.isEmpty()) {
			model.addAttribute("app", app);
			model.addAttribute("isNew", false);
			model.addAttribute("errors", errors);
			model.addAttribute("domains", domainService.allDomains());
			model.addAttribute("homelabs", homelabs());
			model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
			return "edit-app";
		}

		boringProxyClient.deleteTunnel(fqdn);
		Thread.sleep(2000);
		TunnelDto tunnel = boringProxyClient.createTunnel(tunnelMapper.toCreateTunnelRequest(app, session.owner()));
		if (app.isRemoteAccessMode()) {
			app = app.withExposedPort(tunnel.tunnelPort());
		}
		if (!fqdn.equals(app.fqdn())) {
			exposedAppStore.delete(fqdn);
		}
		exposedAppStore.save(app);
		return "redirect:/apps";
	}

	@PostMapping("/apps/{fqdn}/delete")
	public String delete(@PathVariable String fqdn) {
		boringProxyClient.deleteTunnel(fqdn);
		exposedAppStore.delete(fqdn);
		return "redirect:/apps";
	}

	/** Every ordinary Homelab except "This Server" -- that one is reserved for the Local Websites feature, not user-selectable here. */
	private List<String> homelabs() {
		return boringProxyClient.listAgents().keySet().stream()
				.filter(name -> !name.equals(thisServerAgentProperties.agentName()))
				.sorted()
				.toList();
	}

	/**
	 * existing is the previously stored record when editing (null when adding) --
	 * used only to keep the current encrypted credential when the form's secret
	 * field is submitted blank, the same "leave blank to keep unchanged"
	 * convention the rest of an edit already follows.
	 */
	private ExposedApp toExposedApp(ExposedAppForm form, ExposedApp existing) {
		boolean networkService = form.type() == ExposedAppType.NETWORK_SERVICE;
		NetworkServiceMode mode = networkService ? (form.mode() != null ? form.mode() : NetworkServiceMode.RAW_TCP) : null;
		boolean remoteAccess = networkService && mode != NetworkServiceMode.RAW_TCP;

		String domain = remoteAccess ? properties.primaryDomain()
				: form.domain() == null || form.domain().isBlank() ? properties.primaryDomain() : form.domain();
		String trimmedSubdomain = form.subdomain() == null ? null : form.subdomain().trim().toLowerCase();
		String subdomain = networkService
				? (trimmedSubdomain == null || trimmedSubdomain.isBlank() ? generateUniqueSubdomain(domain) : trimmedSubdomain)
				: (trimmedSubdomain == null || trimmedSubdomain.isBlank() ? null : trimmedSubdomain);
		String name = networkService && form.name() != null && !form.name().isBlank() ? form.name().trim() : null;

		String username = remoteAccess ? blankToNull(form.username()) : null;
		boolean ignoreCertificate = remoteAccess && Boolean.TRUE.equals(form.ignoreCertificate());
		String encryptedSecret = !remoteAccess ? null
				: form.secret() == null || form.secret().isBlank()
						? existing != null ? existing.encryptedSecret() : null
						: cipher.encrypt(form.secret());

		return new ExposedApp(subdomain, name, form.homelabName(), form.type(),
				networkService ? null : form.protocol(),
				form.host(), form.port() != null ? form.port() : 0, remoteAccess ? null : form.exposedPort(),
				form.tlsMode(), !networkService && Boolean.TRUE.equals(form.ssoProtected()), domain,
				mode, username, encryptedSecret, ignoreCertificate);
	}

	/** Random internal subdomain for a Network Service, retried until it doesn't collide with an existing tunnel on domain. */
	private String generateUniqueSubdomain(String domain) {
		Map<String, TunnelDto> existing = boringProxyClient.listTunnels();
		while (true) {
			String candidate = "svc-" + UUID.randomUUID().toString().substring(0, 8);
			String fqdn = candidate + "." + domain;
			if (existing.keySet().stream().noneMatch(d -> d.equalsIgnoreCase(fqdn))) {
				return candidate;
			}
		}
	}

	/** originalFqdn is null when adding, and the FQDN being edited when updating (excluded from the collision check). */
	private List<String> validate(ExposedApp app, PortalSession session, String originalFqdn) {
		List<String> errors = new ArrayList<>();

		if (app.subdomain() != null && !app.subdomain().isBlank() && !DnsLabelValidator.isValid(app.subdomain())) {
			errors.add("Subdomain can only contain letters, numbers, and hyphens, and cannot start or end with a hyphen.");
		}
		if (!domainService.exists(app.domain())) {
			errors.add("Unknown domain.");
			return errors;
		}
		// The reserved subdomains below only ever exist under the primary domain (see docker-compose.yaml) --
		// the same label under a secondary domain is a perfectly ordinary, unreserved app domain. A blank
		// subdomain (apex) never collides with any of these, since they're all subdomains of the primary
		// domain, not the primary domain itself.
		if (app.subdomain() != null && app.domain().equals(properties.primaryDomain())) {
			if (app.subdomain().equalsIgnoreCase(properties.adminSubdomain())) {
				errors.add("\"" + app.subdomain() + "\" is reserved for the BoringProxy admin portal itself.");
			}
			if (app.subdomain().equalsIgnoreCase(properties.portalSubdomain())) {
				errors.add("\"" + app.subdomain() + "\" is reserved for the Selfie Proxy admin portal itself.");
			}
			if (app.subdomain().equalsIgnoreCase(properties.authSubdomain())) {
				errors.add("\"" + app.subdomain() + "\" is reserved for Selfie Proxy's bundled identity provider itself.");
			}
			if (app.subdomain().equalsIgnoreCase(properties.consoleSubdomain())) {
				errors.add("\"" + app.subdomain() + "\" is reserved for Selfie Proxy's browser SSH/RDP/VNC console itself.");
			}
		}

		String fqdn = tunnelMapper.fqdn(app);
		Map<String, TunnelDto> existing = boringProxyClient.listTunnels();
		boolean taken = existing.keySet().stream()
				.anyMatch(domain -> domain.equalsIgnoreCase(fqdn)
						&& (originalFqdn == null || !domain.equalsIgnoreCase(originalFqdn)));
		if (taken) {
			errors.add(app.subdomain() != null && !app.subdomain().isBlank()
					? "Subdomain \"" + app.subdomain() + "\" is already in use."
					: "\"" + app.domain() + "\" is already in use.");
		}

		if (app.ssoProtected() && !app.canProtectWithSso()) {
			errors.add("Single sign on protection requires Web Application, HTTPS, and the recommended End-to-end encrypted option.");
		}

		if (app.isNetworkService() && (app.name() == null || app.name().isBlank())) {
			errors.add("Name is required for a network service.");
		}

		if (app.isNetworkService() && app.effectiveMode() == NetworkServiceMode.RAW_TCP) {
			if (app.exposedPort() == null) {
				errors.add("Exposed port to the internet is required for a network service.");
			} else if (app.exposedPort() <= RESERVED_PORT_MAX) {
				errors.add("Port " + app.exposedPort() + " is reserved for system services and cannot be exposed.");
			} else {
				boolean portTaken = existing.entrySet().stream()
						.anyMatch(e -> e.getValue().allowExternalTcp()
								&& "passthrough".equals(e.getValue().tlsTermination())
								&& e.getValue().tunnelPort() == app.exposedPort()
								&& (originalFqdn == null || !e.getKey().equals(originalFqdn)));
				if (portTaken) {
					errors.add("Port " + app.exposedPort() + " is already exposed by another application.");
				}
			}
		}

		return errors;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
