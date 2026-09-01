package online.selfieproxy.portal.web;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
import online.selfieproxy.portal.domain.AuthExemptPaths;
import online.selfieproxy.portal.domain.ServerProtocol;
import online.selfieproxy.portal.domain.DnsLabelValidator;
import online.selfieproxy.portal.domain.DomainService;
import online.selfieproxy.portal.domain.GatewayPortsChecker;
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
import online.selfieproxy.portal.domain.TunnelRepairService;
import online.selfieproxy.portal.domain.WebAuthMethod;
import online.selfieproxy.portal.domain.WebConfig;
import online.selfieproxy.portal.security.NetworkServiceCredentialCipher;
import online.selfieproxy.portal.session.PortalSession;
import online.selfieproxy.portal.session.PortalSessions;

import jakarta.servlet.http.HttpServletRequest;

@Controller
public class ServerController {

	/** Well-known/system port range (SSH, HTTPS, ...) that must never be exposed as Port Forwarding's public port. */
	private static final int RESERVED_PORT_MAX = 1023;
	/** The highest possible TCP/UDP port number -- applies to every port field on the edit page. */
	private static final int MAX_PORT = 65535;
	/** Deliberately capped, not a port range -- each entry is its own boringproxy tunnel. */
	private static final int MAX_PORT_FORWARDING_ENTRIES = 8;
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
	private final GatewayPortsChecker gatewayPortsChecker;
	private final TunnelRepairService tunnelRepairService;

	public ServerController(BoringProxyClient boringProxyClient, TunnelMapper tunnelMapper,
			BoringProxyProperties properties, ServerStore serverStore, NetworkServiceCredentialCipher cipher,
			ThisServerAgentProperties thisServerAgentProperties, DomainService domainService,
			AgentStatusService agentStatusService, LastUsedServerDefaultsStore lastUsedServerDefaultsStore,
			HiddenTunnelFqdnAssigner fqdnAssigner, GatewayPortsChecker gatewayPortsChecker,
			TunnelRepairService tunnelRepairService) {
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
		this.gatewayPortsChecker = gatewayPortsChecker;
		this.tunnelRepairService = tunnelRepairService;
	}

	@GetMapping("/servers/new")
	public String newServer(Model model) {
		List<String> homelabs = homelabs();
		LastUsedServerDefaults lastUsed = lastUsedServerDefaultsStore.load();
		String defaultDomain = lastUsed != null && lastUsed.domain() != null && domainService.exists(lastUsed.domain())
				? lastUsed.domain() : properties.primaryDomain();
		String defaultHomelab = lastUsed != null && lastUsed.homelabName() != null && homelabs.contains(lastUsed.homelabName())
				? lastUsed.homelabName() : homelabs.stream().findFirst().orElse(null);
		// Web starts enabled and SSO-protected by default -- the common case is a single-sign-on
		// protected website, and the admin opts out rather than in. Every other protocol checkbox
		// still starts unchecked -- the admin picks which of those to enable.
		WebConfig defaultWeb = new WebConfig(Protocol.HTTPS, 443, WebAuthMethod.SSO, null, null, null, null, null);
		Server server = new Server("", defaultDomain, defaultHomelab, "127.0.0.1", defaultWeb, null, null, null);
		model.addAttribute("server", server);
		model.addAttribute("isNew", true);
		model.addAttribute("domains", domainService.allDomains());
		model.addAttribute("homelabs", homelabs);
		model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
		model.addAttribute("gatewayPortsConfigured", gatewayPortsChecker.isConfigured());
		return "edit-server";
	}

	@GetMapping("/servers/{fqdn}/edit")
	public String editServer(@PathVariable String fqdn, Model model) {
		Server server = serverStore.find(fqdn);
		if (server == null) {
			return "redirect:/servers";
		}
		tunnelRepairService.createMissing(server);
		server = serverStore.find(fqdn);
		model.addAttribute("server", server);
		model.addAttribute("isNew", false);
		model.addAttribute("domains", domainService.allDomains());
		model.addAttribute("homelabs", homelabs());
		model.addAttribute("homelabOnline", agentStatusService.onlineByAgentName());
		model.addAttribute("certPending", certPending(server));
		model.addAttribute("gatewayPortsConfigured", gatewayPortsChecker.isConfigured());
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
			model.addAttribute("gatewayPortsConfigured", gatewayPortsChecker.isConfigured());
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
			model.addAttribute("gatewayPortsConfigured", gatewayPortsChecker.isConfigured());
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
					.forEach(plan -> boringProxyClient.deleteTunnelIfPresent(plan.fqdn()));
			tunnelMapper.portForwardingTunnelPlans(server, OWNER)
					.forEach(plan -> boringProxyClient.deleteTunnelIfPresent(plan.fqdn()));
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
		TunnelDto tunnel = boringProxyClient.getTunnelOrNull(server.fqdn());
		return tunnel != null && tunnel.certPending();
	}

	/**
	 * Diffs existing's tunnel plans against desired's per protocol (ServerProtocol.WEB/TERMINAL/
	 * REMOTE_DESKTOP), plus a separate fqdn-keyed diff for Port Forwarding (which can have several
	 * tunnels at once, so it can't be diffed by a single ServerProtocol key the way the other three
	 * are): a protocol/entry whose plan is unchanged is left completely alone (no delete/recreate --
	 * eg. toggling the Remote Desktop checkbox must never bounce an unrelated live SSH session, and
	 * editing one Port Forwarding entry must never bounce its siblings), a removed one is deleted, an
	 * added or changed one is (re)created. Only one 2s wait total is paid, not one per changed
	 * protocol/entry. existing is null on create, so every enabled protocol/entry is simply created
	 * with nothing to delete first.
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

		Map<String, TunnelMapper.ProtocolTunnel> oldPortForwards = existing == null ? Map.of()
				: tunnelMapper.portForwardingTunnelPlans(existing, owner).stream()
						.collect(Collectors.toMap(TunnelMapper.ProtocolTunnel::fqdn, p -> p));
		Map<String, TunnelMapper.ProtocolTunnel> newPortForwards = tunnelMapper.portForwardingTunnelPlans(desired, owner).stream()
				.collect(Collectors.toMap(TunnelMapper.ProtocolTunnel::fqdn, p -> p));
		List<TunnelMapper.ProtocolTunnel> portForwardsToCreate = new ArrayList<>();
		Set<String> allPortForwardFqdns = new HashSet<>(oldPortForwards.keySet());
		allPortForwardFqdns.addAll(newPortForwards.keySet());
		for (String fqdn : allPortForwardFqdns) {
			TunnelMapper.ProtocolTunnel oldPlan = oldPortForwards.get(fqdn);
			TunnelMapper.ProtocolTunnel newPlan = newPortForwards.get(fqdn);
			if (Objects.equals(oldPlan, newPlan)) {
				continue;
			}
			if (oldPlan != null) {
				toDelete.add(oldPlan.fqdn());
			}
			if (newPlan != null) {
				portForwardsToCreate.add(newPlan);
			}
		}

		toDelete.forEach(boringProxyClient::deleteTunnelIfPresent);
		if (!toDelete.isEmpty() && (!toCreate.isEmpty() || !portForwardsToCreate.isEmpty())) {
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
		portForwardsToCreate.forEach(plan -> boringProxyClient.createTunnel(plan.request()));
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

		// Mutable and grown as each Port Forwarding entry below is assigned its own hidden fqdn, so
		// two entries in the same submission can never collide with each other.
		Set<String> ownHiddenFqdns = new HashSet<>();
		if (existing != null) {
			if (existing.terminal() != null) {
				ownHiddenFqdns.add(existing.terminal().fqdn());
			}
			if (existing.remoteDesktop() != null) {
				ownHiddenFqdns.add(existing.remoteDesktop().fqdn());
			}
			if (existing.portForwarding() != null) {
				existing.portForwarding().forEach(entry -> ownHiddenFqdns.add(entry.fqdn()));
			}
		}

		WebConfig web;
		if (enabled(form.webEnabled())) {
			// Unlike the other three protocols, this branch used to ignore `existing` entirely --
			// it now has credentials to carry forward, so it needs the same blank-means-unchanged
			// treatment Terminal/Remote Desktop already get.
			WebConfig previousWeb = existing != null ? existing.web() : null;
			WebAuthMethod authMethod = form.webAuthMethod() != null ? form.webAuthMethod() : WebAuthMethod.SSO;
			// Only the selected method's credentials are kept. Switching away from a method and
			// saving deliberately discards its credential rather than parking it: leaving a stale
			// secret in servers.json for a gate that's no longer active is exactly the kind of
			// thing an admin would never think to clean up.
			String basicUsername = authMethod == WebAuthMethod.BASIC ? blankToNull(form.webBasicUsername()) : null;
			String basicPassword = authMethod == WebAuthMethod.BASIC
					? resolveSecret(form.webBasicPassword(), previousWeb != null ? previousWeb.basicPassword() : null)
					: null;
			String tokenHeaderName = authMethod == WebAuthMethod.TOKEN ? blankToNull(form.webTokenHeaderName()) : null;
			String tokenValue = authMethod == WebAuthMethod.TOKEN
					? resolveSecret(form.webTokenValue(), previousWeb != null ? previousWeb.tokenValue() : null)
					: null;
			// Deliberately not conditioned on authMethod, unlike the credentials above: an
			// exception is a path, not a secret, and means the same thing under every gate --
			// discarding it on a switch to another method (or to NONE and back while testing
			// something) would lose work the admin has no reason to expect to be method-specific.
			List<String> exemptPaths = AuthExemptPaths.parse(form.webAuthExemptPaths());
			web = new WebConfig(form.webProtocol() != null ? form.webProtocol() : Protocol.HTTPS,
					form.webPort() != null ? form.webPort() : 0, authMethod,
					basicUsername, basicPassword, tokenHeaderName, tokenValue, exemptPaths);
		} else {
			web = null;
		}

		TerminalConfig terminal;
		if (enabled(form.terminalEnabled())) {
			int port = form.terminalPort() != null ? form.terminalPort() : 22;
			String hiddenFqdn = fqdnAssigner.assign(appFqdn, "terminal", properties.primaryDomain(), ownHiddenFqdns);
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
			String hiddenFqdn = fqdnAssigner.assign(appFqdn, "remotedesktop", properties.primaryDomain(), ownHiddenFqdns);
			Integer previousExposedPort = existing != null && existing.remoteDesktop() != null
					? existing.remoteDesktop().exposedPort() : null;
			String previousSecret = existing != null && existing.remoteDesktop() != null
					? existing.remoteDesktop().encryptedSecret() : null;
			remoteDesktop = new RemoteDesktopConfig(hiddenFqdn, protocol, port, previousExposedPort,
					blankToNull(form.remoteDesktopUsername()),
					resolveSecret(form.remoteDesktopSecret(), previousSecret),
					true);
		} else {
			remoteDesktop = null;
		}

		List<PortForwardingConfig> portForwarding;
		if (enabled(form.portForwardingEnabled())) {
			// Only TCP exists today -- no per-entry (or shared) protocol choice to submit.
			PortForwardingProtocol protocol = PortForwardingProtocol.TCP;
			List<Integer> targetPorts = form.portForwardingTargetPort() != null ? form.portForwardingTargetPort() : List.of();
			List<Integer> publicPorts = form.portForwardingPublicPort() != null ? form.portForwardingPublicPort() : List.of();
			List<String> descriptions = form.portForwardingDescription() != null ? form.portForwardingDescription() : List.of();
			int entryCount = Math.min(targetPorts.size(), publicPorts.size());
			portForwarding = new ArrayList<>();
			for (int i = 0; i < entryCount; i++) {
				int publicPort = publicPorts.get(i) != null ? publicPorts.get(i) : 0;
				int targetPort = targetPorts.get(i) != null ? targetPorts.get(i) : 0;
				String description = i < descriptions.size() ? blankToNull(descriptions.get(i)) : null;
				String hiddenFqdn = fqdnAssigner.assign(appFqdn, String.valueOf(publicPort), domain, ownHiddenFqdns);
				ownHiddenFqdns.add(hiddenFqdn);
				portForwarding.add(new PortForwardingConfig(hiddenFqdn, protocol, publicPort, targetPort, description));
			}
		} else {
			portForwarding = null;
		}

		return new Server(subdomain, domain, form.homelabName(), form.host(), web, terminal, remoteDesktop, portForwarding);
	}

	/**
	 * The credentials are checked against the already-resolved WebConfig rather than the raw form,
	 * so a blank field on an edit that kept a stored credential passes -- resolveSecret has already
	 * carried the previous value forward by this point.
	 */
	private void validateWebAuth(WebConfig web, List<String> errors) {
		if (web.authMethodOrDefault() == WebAuthMethod.BASIC) {
			if (web.basicUsername() == null) {
				errors.add("Enter a username for basic authentication.");
			}
			if (web.basicPassword() == null) {
				errors.add("Enter a password for basic authentication.");
			}
		}
		if (web.authMethodOrDefault() == WebAuthMethod.TOKEN) {
			// The header name is required rather than quietly defaulting to Authorization: the
			// admin has to hand the exact header to whoever configures the client, so leaving it
			// implicit here just moves the guesswork somewhere it can't be checked. The form
			// pre-fills "Authorization" so the common case is still a single field to fill in.
			if (web.tokenHeaderName() == null) {
				errors.add("Enter a header name for token header authentication.");
			} else if (!isHttpHeaderName(web.tokenHeaderName())) {
				errors.add("Header name can only contain letters, numbers, and the characters !#$%&'*+-.^_`|~");
			}
			if (web.tokenValue() == null) {
				errors.add("Enter a token for token header authentication.");
			}
		}
	}

	/**
	 * Checked against the already-parsed list rather than the raw textarea, so the messages name
	 * exactly the patterns that will be stored. Skipped entirely under NONE: the list is still kept
	 * in servers.json (see toServer) but never sent to boringproxy, so nothing it says can be wrong
	 * yet -- rejecting a save over a pattern that currently does nothing would just block an admin
	 * mid-way through switching a Server back on.
	 */
	private void validateAuthExemptPaths(WebConfig web, List<String> errors) {
		if (web.authMethodOrDefault() == WebAuthMethod.NONE) {
			return;
		}
		errors.addAll(AuthExemptPaths.validate(web.authExemptPathsOrEmpty()));
	}

	/** RFC 9110's `token` production -- the only thing that can legally name a header. */
	private boolean isHttpHeaderName(String value) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
					|| "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
			if (!allowed) {
				return false;
			}
		}
		return !value.isEmpty();
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
	 * editing (null when adding) -- every one of its current Port Forwarding hidden FQDNs must also
	 * be excluded from the public-port uniqueness check below, since at validation time those old
	 * tunnels are still live in boringproxy (syncTunnels only deletes/recreates after validation
	 * passes) and would otherwise look like a different Application already using the same port.
	 */
	private List<String> validate(Server server, String originalFqdn, Server existing) {
		List<String> errors = new ArrayList<>();

		if (server.subdomain() != null && !server.subdomain().isBlank() && !DnsLabelValidator.isValid(server.subdomain())) {
			errors.add("Subdomain can only contain letters, numbers, and hyphens, and cannot start or end with a hyphen.");
		}
		// A blank subdomain (apex) only makes sense for Web -- a whole domain hosting one website.
		// Terminal/RemoteDesktop/PortForwarding each route over their own separately-generated hidden
		// tunnel FQDN (see HiddenTunnelFqdnAssigner), never the server's own subdomain, so leaving it
		// blank buys nothing there -- and since a server without Web never registers a live boringproxy
		// tunnel at its own fqdn, two such servers left blank on the same domain wouldn't even be caught
		// by the "already in use" check below (nothing to collide against), silently overwriting one
		// another in ServerStore on save. The subdomain is also the only name the admin has to find this
		// server again on the Servers list/edit page, so requiring it here is a UX necessity too, not
		// just a collision guard.
		if (!server.hasWeb() && (server.subdomain() == null || server.subdomain().isBlank())) {
			errors.add("A subdomain is required unless this server exposes a website at the domain itself.");
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
		boolean takenByTunnel = existingTunnels.keySet().stream()
				.anyMatch(domain -> domain.equalsIgnoreCase(fqdn)
						&& (originalFqdn == null || !domain.equalsIgnoreCase(originalFqdn)));
		// A server without Web never registers a live boringproxy tunnel at its own fqdn (see the
		// subdomain-required check above), so the tunnel-list check above alone can't catch two such
		// servers sharing the same subdomain+domain -- cross-check ServerStore's own records too, or
		// the second save would silently overwrite the first instead of being rejected.
		boolean takenByServer = serverStore.values().stream()
				.anyMatch(other -> other.fqdn().equalsIgnoreCase(fqdn)
						&& (originalFqdn == null || !other.fqdn().equalsIgnoreCase(originalFqdn)));
		if (takenByTunnel || takenByServer) {
			errors.add(server.subdomain() != null && !server.subdomain().isBlank()
					? "Subdomain \"" + server.subdomain() + "\" is already in use."
					: "\"" + server.domain() + "\" is already in use.");
		}

		if (!server.hasWeb() && !server.hasTerminal() && !server.hasRemoteDesktop() && !server.hasPortForwarding()) {
			errors.add("Select at least one protocol to expose.");
		}

		if (server.hasWeb()) {
			validatePortRange(server.web().port(), "Homelab server port", errors);
			validateWebAuth(server.web(), errors);
			validateAuthExemptPaths(server.web(), errors);
		}
		if (server.hasTerminal()) {
			validatePortRange(server.terminal().port(), "Homelab server port", errors);
		}
		if (server.hasRemoteDesktop()) {
			validatePortRange(server.remoteDesktop().port(), "Homelab server port", errors);
		}

		if (server.hasPortForwarding()) {
			List<PortForwardingConfig> portForwarding = server.portForwarding();
			if (portForwarding.size() > MAX_PORT_FORWARDING_ENTRIES) {
				errors.add("Up to " + MAX_PORT_FORWARDING_ENTRIES + " ports can be forwarded.");
			}
			Set<String> previousFqdns = existing != null && existing.portForwarding() != null
					? existing.portForwarding().stream().map(PortForwardingConfig::fqdn).collect(Collectors.toSet())
					: Set.of();
			Set<Integer> publicPortsSeen = new HashSet<>();
			Set<Integer> targetPortsSeen = new HashSet<>();
			for (PortForwardingConfig entry : portForwarding) {
				validatePortRange(entry.targetPort(), "Homelab server port", errors);
				if (!targetPortsSeen.add(entry.targetPort())) {
					errors.add("Homelab server port " + entry.targetPort()
							+ " is used by more than one of this server's own forwarded ports.");
					continue;
				}
				if (entry.publicPort() > MAX_PORT) {
					errors.add("Port " + entry.publicPort() + " is not a valid port number (must be 1-" + MAX_PORT + ").");
					continue;
				}
				if (entry.publicPort() <= RESERVED_PORT_MAX) {
					errors.add("Port " + entry.publicPort() + " is reserved for system services and cannot be exposed.");
					continue;
				}
				if (!publicPortsSeen.add(entry.publicPort())) {
					errors.add("Port " + entry.publicPort() + " is used by more than one of this server's own forwarded ports.");
					continue;
				}
				boolean portTaken = existingTunnels.entrySet().stream()
						.anyMatch(e -> e.getValue().allowExternalTcp()
								&& "passthrough".equals(e.getValue().tlsTermination())
								&& e.getValue().tunnelPort() == entry.publicPort()
								&& !e.getKey().equalsIgnoreCase(entry.fqdn())
								&& previousFqdns.stream().noneMatch(e.getKey()::equalsIgnoreCase));
				if (portTaken) {
					errors.add("Port " + entry.publicPort() + " is already exposed by another server.");
				}
			}
		}

		return errors;
	}

	/** Applies to every port field on the edit page (Web/Terminal/Remote Desktop's homelab-side port, and Port Forwarding's homelab-side port -- Port Forwarding's own public-port range is checked separately alongside its other business rules). */
	private void validatePortRange(int port, String fieldLabel, List<String> errors) {
		if (port < 1 || port > MAX_PORT) {
			errors.add(fieldLabel + " " + port + " is not a valid port number (must be 1-" + MAX_PORT + ").");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
