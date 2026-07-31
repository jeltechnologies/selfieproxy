package online.selfieproxy.portal.web;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import online.selfieproxy.portal.domain.ServerProtocol;
import online.selfieproxy.portal.domain.DnsLabelValidator;
import online.selfieproxy.portal.domain.DomainService;
import online.selfieproxy.portal.domain.Server;
import online.selfieproxy.portal.domain.ServerStore;
import online.selfieproxy.portal.domain.HiddenTunnelFqdnAssigner;
import online.selfieproxy.portal.domain.LastUsedServerDefaults;
import online.selfieproxy.portal.domain.LastUsedServerDefaultsStore;
import online.selfieproxy.portal.domain.PortForwardingConfig;
import online.selfieproxy.portal.domain.PortForwardingProtocol;
import online.selfieproxy.portal.domain.Protocol;
import online.selfieproxy.portal.domain.RemoteDesktopConfig;
import online.selfieproxy.portal.domain.RemoteDesktopProtocol;
import online.selfieproxy.portal.domain.TerminalConfig;
import online.selfieproxy.portal.domain.TunnelMapper;
import online.selfieproxy.portal.domain.WebConfig;
import online.selfieproxy.portal.security.NetworkServiceCredentialCipher;
import online.selfieproxy.portal.session.PortalSession;
import online.selfieproxy.portal.session.PortalSessions;

import jakarta.servlet.http.HttpServletRequest;

@Controller
public class ServerController {

	/** Well-known/system port range (SSH, HTTPS, ...) that must never be exposed as Port Forwarding's public port. */
	private static final int RESERVED_PORT_MAX = 1023;
	private static final long TUNNEL_RECREATE_WAIT_MS = 2000;
	private static final String OWNER = "admin";

	private final BoringProxyClient boringProxyClient;
	private final TunnelMapper tunnelMapper;
	private final BoringProxyProperties properties;
	private final ServerStore serverStore;
	private final NetworkServiceCredentialCipher cipher;
	private final ThisServerAgentProperties thisServerAgentProperties;
	private final DomainService domainService;
	private final AgentStatusService agentStatusService;
	private final LastUsedServerDefaultsStore lastUsedServerDefaultsStore;
	private final HiddenTunnelFqdnAssigner fqdnAssigner;

	public ServerController(BoringProxyClient boringProxyClient, TunnelMapper tunnelMapper,
			BoringProxyProperties properties, ServerStore serverStore, NetworkServiceCredentialCipher cipher,
			ThisServerAgentProperties thisServerAgentProperties, DomainService domainService,
			AgentStatusService agentStatusService, LastUsedServerDefaultsStore lastUsedServerDefaultsStore,
			HiddenTunnelFqdnAssigner fqdnAssigner) {
		this.boringProxyClient = boringProxyClient;
		this.tunnelMapper = tunnelMapper;
		this.properties = properties;
		this.serverStore = serverStore;
		this.cipher = cipher;
		this.thisServerAgentProperties = thisServerAgentProperties;
		this.domainService = domainService;
		this.agentStatusService = agentStatusService;
		this.lastUsedServerDefaultsStore = lastUsedServerDefaultsStore;
		this.fqdnAssigner = fqdnAssigner;
	}

	@GetMapping("/servers/new")
	public String newServer(Model model) {
		List<String> homelabs = homelabs();
		LastUsedServerDefaults lastUsed = lastUsedServerDefaultsStore.load();
		String defaultDomain = lastUsed != null && lastUsed.domain() != null && domainService.exists(lastUsed.domain())
				? lastUsed.domain() : properties.primaryDomain();
		String defaultHomelab = lastUsed != null && lastUsed.homelabName() != null && homelabs.contains(lastUsed.homelabName())
				? lastUsed.homelabName() : homelabs.stream().findFirst().orElse(null);
		// Every protocol checkbox starts unchecked for a brand-new Application -- the admin picks
		// which ones to enable.
		Server server = new Server("", defaultDomain, defaultHomelab, "127.0.0.1", null, null, null, null);
		model.addAttribute("server", server);
		model.addAttribute("isNew", true);
		model.addAttribute("domains", domainService.allDomains());
		model.addAttribute("homelabs", homelabs);
		model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
		return "edit-server";
	}

	@GetMapping("/servers/{fqdn}/edit")
	public String editServer(@PathVariable String fqdn, Model model) {
		Server server = serverStore.find(fqdn);
		if (server == null) {
			return "redirect:/servers";
		}
		model.addAttribute("server", server);
		model.addAttribute("isNew", false);
		model.addAttribute("domains", domainService.allDomains());
		model.addAttribute("homelabs", homelabs());
		model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
		model.addAttribute("certPending", certPending(server));
		return "edit-server";
	}

	@PostMapping("/servers")
	public String create(@ModelAttribute ServerForm form, HttpServletRequest request, Model model) {
		PortalSession session = PortalSessions.get(request.getSession(false));
		Server server = toServer(form, null);

		List<String> errors = validate(server, null, null);
		if (!errors.isEmpty()) {
			model.addAttribute("server", server);
			model.addAttribute("isNew", true);
			model.addAttribute("errors", errors);
			model.addAttribute("domains", domainService.allDomains());
			model.addAttribute("homelabs", homelabs());
			model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
			return "edit-server";
		}

		server = syncTunnels(null, server, session.owner());
		rememberDefaults(server);
		serverStore.save(server);
		return "redirect:/servers";
	}

	private void rememberDefaults(Server server) {
		lastUsedServerDefaultsStore.save(new LastUsedServerDefaults(server.domain(), server.homelabName()));
	}

	@PostMapping("/servers/{fqdn}")
	public String update(@PathVariable String fqdn, @ModelAttribute ServerForm form,
			HttpServletRequest request, Model model) {
		PortalSession session = PortalSessions.get(request.getSession(false));
		Server existing = serverStore.find(fqdn);
		Server server = toServer(form, existing);

		List<String> errors = validate(server, fqdn, existing);
		if (!errors.isEmpty()) {
			model.addAttribute("server", server);
			model.addAttribute("isNew", false);
			model.addAttribute("errors", errors);
			model.addAttribute("domains", domainService.allDomains());
			model.addAttribute("homelabs", homelabs());
			model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
			return "edit-server";
		}

		server = syncTunnels(existing, server, session.owner());
		if (!fqdn.equals(server.fqdn())) {
			serverStore.delete(fqdn);
		}
		rememberDefaults(server);
		serverStore.save(server);
		return "redirect:/servers";
	}

	@PostMapping("/servers/{fqdn}/delete")
	public String delete(@PathVariable String fqdn) {
		Server server = serverStore.find(fqdn);
		if (server != null) {
			tunnelMapper.tunnelPlans(server, OWNER).values()
					.forEach(plan -> boringProxyClient.deleteTunnel(plan.fqdn()));
		}
		serverStore.delete(fqdn);
		return "redirect:/servers";
	}

	/** Every ordinary Homelab except "This Server" -- that one is reserved for the Local Websites feature, not user-selectable here. */
	private List<String> homelabs() {
		return boringProxyClient.listAgents().keySet().stream()
				.filter(name -> !name.equals(thisServerAgentProperties.agentName()))
				.sorted()
				.toList();
	}

	private boolean certPending(Server server) {
		if (!server.hasWeb()) {
			return false;
		}
		TunnelDto tunnel = boringProxyClient.getTunnel(server.fqdn());
		return tunnel.certPending();
	}

	/**
	 * Diffs existing's tunnel plans against desired's per protocol (ServerProtocol.WEB/TERMINAL/
	 * REMOTE_DESKTOP/PORT_FORWARDING): a protocol whose plan is unchanged is left completely alone
	 * (no delete/recreate -- eg. toggling the Remote Desktop checkbox must never bounce an unrelated
	 * live SSH session), a removed protocol is deleted, an added or changed one is (re)created. Only
	 * one 2s wait total is paid, not one per changed protocol. existing is null on create, so every
	 * enabled protocol is simply created with nothing to delete first.
	 */
	private Server syncTunnels(Server existing, Server desired, String owner) {
		Map<ServerProtocol, TunnelMapper.ProtocolTunnel> oldPlans = existing == null
				? Map.of() : tunnelMapper.tunnelPlans(existing, owner);
		Map<ServerProtocol, TunnelMapper.ProtocolTunnel> newPlans = tunnelMapper.tunnelPlans(desired, owner);

		List<String> toDelete = new ArrayList<>();
		Map<ServerProtocol, TunnelMapper.ProtocolTunnel> toCreate = new EnumMap<>(ServerProtocol.class);
		for (ServerProtocol protocol : ServerProtocol.values()) {
			TunnelMapper.ProtocolTunnel oldPlan = oldPlans.get(protocol);
			TunnelMapper.ProtocolTunnel newPlan = newPlans.get(protocol);
			if (Objects.equals(oldPlan, newPlan)) {
				continue;
			}
			if (oldPlan != null) {
				toDelete.add(oldPlan.fqdn());
			}
			if (newPlan != null) {
				toCreate.put(protocol, newPlan);
			}
		}

		toDelete.forEach(boringProxyClient::deleteTunnel);
		if (!toDelete.isEmpty() && !toCreate.isEmpty()) {
			sleep();
		}

		Server result = desired;
		for (Map.Entry<ServerProtocol, TunnelMapper.ProtocolTunnel> entry : toCreate.entrySet()) {
			TunnelDto created = boringProxyClient.createTunnel(entry.getValue().request());
			if (entry.getKey() == ServerProtocol.TERMINAL) {
				result = result.withTerminalExposedPort(created.tunnelPort());
			} else if (entry.getKey() == ServerProtocol.REMOTE_DESKTOP) {
				result = result.withRemoteDesktopExposedPort(created.tunnelPort());
			}
		}
		return result;
	}

	private void sleep() {
		try {
			Thread.sleep(TUNNEL_RECREATE_WAIT_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for tunnel teardown", e);
		}
	}

	/**
	 * existing is the previously stored record when editing (null when adding) -- used to keep
	 * each protocol's current encrypted credential when its form secret field is submitted blank,
	 * and to exclude this Application's own current hidden tunnel FQDNs from collision checks when
	 * regenerating them (see HiddenTunnelFqdnAssigner).
	 */
	private Server toServer(ServerForm form, Server existing) {
		String domain = form.domain() == null || form.domain().isBlank() ? properties.primaryDomain() : form.domain();
		String trimmedSubdomain = form.subdomain() == null ? null : form.subdomain().trim().toLowerCase();
		String subdomain = trimmedSubdomain == null || trimmedSubdomain.isBlank() ? null : trimmedSubdomain;
		String appFqdn = subdomain == null ? domain : subdomain + "." + domain;

		Set<String> ownHiddenFqdns = existing == null ? Set.of() : Stream.of(
				existing.terminal() != null ? existing.terminal().fqdn() : null,
				existing.remoteDesktop() != null ? existing.remoteDesktop().fqdn() : null,
				existing.portForwarding() != null ? existing.portForwarding().fqdn() : null)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());

		WebConfig web = enabled(form.webEnabled())
				? new WebConfig(form.webProtocol() != null ? form.webProtocol() : Protocol.HTTPS,
						form.webPort() != null ? form.webPort() : 0, Boolean.TRUE.equals(form.webSsoProtected()))
				: null;

		TerminalConfig terminal;
		if (enabled(form.terminalEnabled())) {
			int port = form.terminalPort() != null ? form.terminalPort() : 22;
			String hiddenFqdn = fqdnAssigner.assign(appFqdn, port, properties.primaryDomain(), ownHiddenFqdns);
			Integer previousExposedPort = existing != null && existing.terminal() != null
					? existing.terminal().exposedPort() : null;
			String previousSecret = existing != null && existing.terminal() != null
					? existing.terminal().encryptedSecret() : null;
			terminal = new TerminalConfig(hiddenFqdn, port, previousExposedPort, blankToNull(form.terminalUsername()),
					resolveSecret(form.terminalSecret(), previousSecret));
		} else {
			terminal = null;
		}

		RemoteDesktopConfig remoteDesktop;
		if (enabled(form.remoteDesktopEnabled())) {
			RemoteDesktopProtocol protocol = form.remoteDesktopProtocol() != null
					? form.remoteDesktopProtocol() : RemoteDesktopProtocol.RDP;
			int port = form.remoteDesktopPort() != null ? form.remoteDesktopPort() : protocol.defaultPort();
			String hiddenFqdn = fqdnAssigner.assign(appFqdn, port, properties.primaryDomain(), ownHiddenFqdns);
			Integer previousExposedPort = existing != null && existing.remoteDesktop() != null
					? existing.remoteDesktop().exposedPort() : null;
			String previousSecret = existing != null && existing.remoteDesktop() != null
					? existing.remoteDesktop().encryptedSecret() : null;
			remoteDesktop = new RemoteDesktopConfig(hiddenFqdn, protocol, port, previousExposedPort,
					blankToNull(form.remoteDesktopUsername()),
					resolveSecret(form.remoteDesktopSecret(), previousSecret),
					Boolean.TRUE.equals(form.remoteDesktopIgnoreCertificate()));
		} else {
			remoteDesktop = null;
		}

		PortForwardingConfig portForwarding;
		if (enabled(form.portForwardingEnabled())) {
			PortForwardingProtocol protocol = form.portForwardingProtocol() != null
					? form.portForwardingProtocol() : PortForwardingProtocol.TCP;
			int publicPort = form.portForwardingPublicPort() != null ? form.portForwardingPublicPort() : 0;
			String hiddenFqdn = fqdnAssigner.assign(appFqdn, publicPort, domain, ownHiddenFqdns);
			portForwarding = new PortForwardingConfig(hiddenFqdn, protocol, publicPort,
					form.portForwardingTargetPort() != null ? form.portForwardingTargetPort() : 0);
		} else {
			portForwarding = null;
		}

		return new Server(subdomain, domain, form.homelabName(), form.host(), web, terminal, remoteDesktop, portForwarding);
	}

	private boolean enabled(Boolean value) {
		return Boolean.TRUE.equals(value);
	}

	private String resolveSecret(String submittedSecret, String previousEncryptedSecret) {
		return submittedSecret == null || submittedSecret.isBlank() ? previousEncryptedSecret : cipher.encrypt(submittedSecret);
	}

	/**
	 * originalFqdn is null when adding, and the Application's own current FQDN when updating
	 * (excluded from the Row 1 collision check). existing is the previously-stored record when
	 * editing (null when adding) -- its own current Port Forwarding hidden FQDN, if any, must also
	 * be excluded from the public-port uniqueness check below, since at validation time that old
	 * tunnel is still live in boringproxy (syncTunnels only deletes/recreates after validation
	 * passes) and would otherwise look like a different Application already using the same port.
	 */
	private List<String> validate(Server server, String originalFqdn, Server existing) {
		List<String> errors = new ArrayList<>();

		if (server.subdomain() != null && !server.subdomain().isBlank() && !DnsLabelValidator.isValid(server.subdomain())) {
			errors.add("Subdomain can only contain letters, numbers, and hyphens, and cannot start or end with a hyphen.");
		}
		if (!domainService.exists(server.domain())) {
			errors.add("Unknown domain.");
			return errors;
		}
		// The reserved subdomains below only ever exist under the primary domain (see docker-compose.yaml) --
		// the same label under a secondary domain is a perfectly ordinary, unreserved server domain. A blank
		// subdomain (apex) never collides with any of these, since they're all subdomains of the primary
		// domain, not the primary domain itself.
		if (server.subdomain() != null && server.domain().equals(properties.primaryDomain())) {
			if (server.subdomain().equalsIgnoreCase(properties.adminSubdomain())) {
				errors.add("\"" + server.subdomain() + "\" is reserved for the BoringProxy admin portal itself.");
			}
			if (server.subdomain().equalsIgnoreCase(properties.portalSubdomain())) {
				errors.add("\"" + server.subdomain() + "\" is reserved for the Selfie Proxy admin portal itself.");
			}
			if (server.subdomain().equalsIgnoreCase(properties.authSubdomain())) {
				errors.add("\"" + server.subdomain() + "\" is reserved for Selfie Proxy's bundled identity provider itself.");
			}
			if (server.subdomain().equalsIgnoreCase(properties.consoleSubdomain())) {
				errors.add("\"" + server.subdomain() + "\" is reserved for Selfie Proxy's browser SSH/RDP/VNC console itself.");
			}
		}

		String fqdn = tunnelMapper.fqdn(server);
		Map<String, TunnelDto> existingTunnels = boringProxyClient.listTunnels();
		boolean taken = existingTunnels.keySet().stream()
				.anyMatch(domain -> domain.equalsIgnoreCase(fqdn)
						&& (originalFqdn == null || !domain.equalsIgnoreCase(originalFqdn)));
		if (taken) {
			errors.add(server.subdomain() != null && !server.subdomain().isBlank()
					? "Subdomain \"" + server.subdomain() + "\" is already in use."
					: "\"" + server.domain() + "\" is already in use.");
		}

		if (!server.hasWeb() && !server.hasTerminal() && !server.hasRemoteDesktop() && !server.hasPortForwarding()) {
			errors.add("Select at least one protocol to expose.");
		}

		if (server.hasPortForwarding()) {
			PortForwardingConfig portForwarding = server.portForwarding();
			if (portForwarding.publicPort() <= RESERVED_PORT_MAX) {
				errors.add("Port " + portForwarding.publicPort() + " is reserved for system services and cannot be exposed.");
			} else {
				String previousFqdn = existing != null && existing.portForwarding() != null
						? existing.portForwarding().fqdn() : null;
				boolean portTaken = existingTunnels.entrySet().stream()
						.anyMatch(e -> e.getValue().allowExternalTcp()
								&& "passthrough".equals(e.getValue().tlsTermination())
								&& e.getValue().tunnelPort() == portForwarding.publicPort()
								&& !e.getKey().equalsIgnoreCase(portForwarding.fqdn())
								&& (previousFqdn == null || !e.getKey().equalsIgnoreCase(previousFqdn)));
				if (portTaken) {
					errors.add("Port " + portForwarding.publicPort() + " is already exposed by another server.");
				}
			}
		}

		return errors;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
