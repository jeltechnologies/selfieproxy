package online.selfieproxy.portal.web;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.BoringProxyException;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.SitesWebserverProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;
import online.selfieproxy.portal.domain.DnsLabelValidator;
import online.selfieproxy.portal.domain.LocalWebsite;
import online.selfieproxy.portal.domain.LocalWebsiteStore;
import online.selfieproxy.portal.domain.StaticSiteProvisioner;
import online.selfieproxy.portal.session.PortalSession;
import online.selfieproxy.portal.session.PortalSessions;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Local Websites: static sites Selfie Proxy hosts itself, entirely
 * independent of the Homelab/Exposed App concept -- no user-run address, no
 * protocol/TLS choice, just a domain. Every website's Tunnel points at the
 * shared selfieproxy-local-websites container through the hidden "This Server"
 * homelab (see ThisServerAgentProperties); this controller builds that
 * Tunnel request directly rather than going through ExposedApp/TunnelMapper,
 * which are exposed-app-specific abstractions this feature deliberately
 * doesn't share.
 */
@Controller
public class LocalWebsiteController {

	private static final Logger log = LoggerFactory.getLogger(LocalWebsiteController.class);

	private static final String OWNER = "admin";

	private final BoringProxyClient boringProxyClient;
	private final LocalWebsiteStore localWebsiteStore;
	private final StaticSiteProvisioner staticSiteProvisioner;
	private final SitesWebserverProperties sitesWebserverProperties;
	private final ThisServerAgentProperties thisServerAgentProperties;
	private final BoringProxyProperties boringProxyProperties;

	public LocalWebsiteController(BoringProxyClient boringProxyClient, LocalWebsiteStore localWebsiteStore,
			StaticSiteProvisioner staticSiteProvisioner, SitesWebserverProperties sitesWebserverProperties,
			ThisServerAgentProperties thisServerAgentProperties, BoringProxyProperties boringProxyProperties) {
		this.boringProxyClient = boringProxyClient;
		this.localWebsiteStore = localWebsiteStore;
		this.staticSiteProvisioner = staticSiteProvisioner;
		this.sitesWebserverProperties = sitesWebserverProperties;
		this.thisServerAgentProperties = thisServerAgentProperties;
		this.boringProxyProperties = boringProxyProperties;
	}

	@GetMapping("/local-websites")
	public String list(Model model) {
		List<LocalWebsite> websites = localWebsiteStore.list();
		model.addAttribute("websites", websites);
		model.addAttribute("boringProxyProperties", boringProxyProperties);

		// DNS correctness only needs checking for ownDomain sites -- a label.DOMAIN site is already
		// guaranteed to resolve here by check-prerequisites' *.DOMAIN wildcard validation at startup
		// (see selfieproxy/CLAUDE.md), but an ownDomain site is a domain the user owns separately and
		// nothing enforces its DNS points at this server, which is exactly what starves it of a real
		// certificate (Let's Encrypt's HTTP-01 challenge has to reach *this* server at that domain to
		// prove ownership -- see the cert-pending investigation above).
		String serverIp = resolveIp(boringProxyProperties.domain());
		Map<String, Boolean> dnsMismatchByDomain = websites.stream()
				.filter(LocalWebsite::ownDomain)
				.collect(Collectors.toMap(LocalWebsite::domain, site -> hasDnsMismatch(site.domain(), serverIp)));
		model.addAttribute("dnsMismatchByDomain", dnsMismatchByDomain);
		model.addAttribute("hasDnsMismatch", dnsMismatchByDomain.values().stream().anyMatch(Boolean::booleanValue));

		// Domains still waiting on a Let's Encrypt certificate (e.g. after hitting a rate limit) --
		// boringproxy serves those over a temporary self-signed certificate in the meantime and
		// keeps retrying in the background, see selfieproxy-reverseproxy's TunnelManager. Scoped to
		// This Server's own tunnels (the opposite filter from DashboardController's Applications
		// page), since a Local Website's tunnel always belongs to the hidden "This Server" homelab.
		// The page-level banner is suppressed for a domain already flagged by the DNS-mismatch check
		// above -- that domain's pending cert is fully explained by its wrong DNS, so showing both
		// warnings would just be saying the same thing twice; a *different* domain still pending for
		// an unrelated reason (e.g. a plain rate limit on a correctly-pointed domain) still triggers
		// it. The per-row badge is untouched -- it's a compact factual indicator, not a redundant
		// explanation, so it stays alongside the row's own DNS warning icon.
		Map<String, Boolean> certPendingByDomain = boringProxyClient.listTunnels().values().stream()
				.filter(tunnel -> thisServerAgentProperties.agentName().equals(tunnel.agentName()))
				.collect(Collectors.toMap(TunnelDto::domain, TunnelDto::certPending));
		model.addAttribute("certPendingByDomain", certPendingByDomain);
		boolean hasPendingCerts = certPendingByDomain.entrySet().stream()
				.anyMatch(e -> e.getValue() && !Boolean.TRUE.equals(dnsMismatchByDomain.get(e.getKey())));
		model.addAttribute("hasPendingCerts", hasPendingCerts);

		return "local-websites";
	}

	@GetMapping("/local-websites/new")
	public String newWebsite(Model model) {
		model.addAttribute("website", new LocalWebsite("", false));
		model.addAttribute("isNew", true);
		model.addAttribute("domain", boringProxyProperties.domain());
		return "edit-local-website";
	}

	@GetMapping("/local-websites/{domain}/edit")
	public String editWebsite(@PathVariable String domain, Model model) {
		LocalWebsite website = localWebsiteStore.find(domain);
		if (website == null) {
			return "redirect:/local-websites";
		}
		model.addAttribute("website", website);
		model.addAttribute("isNew", false);
		model.addAttribute("domain", boringProxyProperties.domain());
		return "edit-local-website";
	}

	@PostMapping("/local-websites")
	public String create(@ModelAttribute LocalWebsiteForm form,
			@RequestParam(value = "websiteZip", required = false) MultipartFile websiteZip,
			HttpServletRequest request, Model model) throws IOException {
		LocalWebsite website = toLocalWebsite(form);

		List<String> errors = validate(website, null);
		if (!errors.isEmpty()) {
			model.addAttribute("website", website);
			model.addAttribute("isNew", true);
			model.addAttribute("errors", errors);
			model.addAttribute("domain", boringProxyProperties.domain());
			return "edit-local-website";
		}

		PortalSession session = PortalSessions.get(request.getSession(false));
		String fqdn = fqdn(website);
		boringProxyClient.createTunnel(toCreateTunnelRequest(fqdn, session.owner()));
		staticSiteProvisioner.provision(fqdn);
		if (websiteZip != null && !websiteZip.isEmpty()) {
			staticSiteProvisioner.replaceContents(fqdn, websiteZip.getInputStream());
		}
		localWebsiteStore.save(website);
		return "redirect:/local-websites";
	}

	@PostMapping("/local-websites/{domain}")
	public String update(@PathVariable String domain, @ModelAttribute LocalWebsiteForm form,
			@RequestParam(value = "websiteZip", required = false) MultipartFile websiteZip,
			HttpServletRequest request, Model model) throws InterruptedException, IOException {
		LocalWebsite website = toLocalWebsite(form);
		boolean hasZip = websiteZip != null && !websiteZip.isEmpty();

		LocalWebsite old = localWebsiteStore.find(domain);
		boolean domainUnchanged = old != null && old.domain().equals(website.domain()) && old.ownDomain() == website.ownDomain();
		if (domainUnchanged && !hasZip) {
			return "redirect:/local-websites";
		}

		List<String> errors = validate(website, domain);
		if (!errors.isEmpty()) {
			model.addAttribute("website", website);
			model.addAttribute("isNew", false);
			model.addAttribute("errors", errors);
			model.addAttribute("domain", boringProxyProperties.domain());
			return "edit-local-website";
		}

		PortalSession session = PortalSessions.get(request.getSession(false));
		String oldFqdn = currentFqdn(domain);
		String newFqdn = fqdn(website);
		if (!domainUnchanged) {
			deleteTunnelIgnoringMissing(oldFqdn);
			Thread.sleep(2000);
			boringProxyClient.createTunnel(toCreateTunnelRequest(newFqdn, session.owner()));
			staticSiteProvisioner.rename(oldFqdn, newFqdn);
		}
		if (hasZip) {
			staticSiteProvisioner.replaceContents(newFqdn, websiteZip.getInputStream());
		}
		if (!domain.equals(website.domain())) {
			localWebsiteStore.delete(domain);
		}
		localWebsiteStore.save(website);
		return "redirect:/local-websites";
	}

	@GetMapping("/local-websites/{domain}/download")
	public void download(@PathVariable String domain, HttpServletResponse response) throws IOException {
		LocalWebsite website = localWebsiteStore.find(domain);
		if (website == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		String fqdn = fqdn(website);
		String safeFileName = fqdn.replaceAll("[^A-Za-z0-9.-]", "_");
		response.setContentType("application/zip");
		response.setHeader("Content-Disposition", "attachment; filename=\"" + safeFileName + ".zip\"");
		staticSiteProvisioner.writeZip(fqdn, response.getOutputStream());
	}

	/** Removes the Tunnel, the NGINX server block, and permanently deletes the site's content directory -- nothing is kept. */
	@PostMapping("/local-websites/{domain}/delete")
	public String delete(@PathVariable String domain) {
		String fqdn = currentFqdn(domain);
		deleteTunnelIgnoringMissing(fqdn);
		localWebsiteStore.delete(domain);
		staticSiteProvisioner.remove(fqdn);
		return "redirect:/local-websites";
	}

	/** The Tunnel record can go stale (eg. already removed from boringproxy some other way) while the LocalWebsite record remains; don't let that block cleanup of the rest. */
	private void deleteTunnelIgnoringMissing(String domain) {
		try {
			boringProxyClient.deleteTunnel(domain);
		} catch (BoringProxyException e) {
			if (!"Tunnel doesn't exist".equals(e.getMessage())) {
				throw e;
			}
			log.warn("Tunnel for local website {} was already gone from boringproxy; continuing cleanup", domain);
		}
	}

	private String normalize(String domain) {
		return domain == null ? "" : domain.trim().toLowerCase();
	}

	private LocalWebsite toLocalWebsite(LocalWebsiteForm form) {
		return new LocalWebsite(normalize(form.domain()), form.ownDomain());
	}

	private String fqdn(LocalWebsite website) {
		return website.ownDomain() ? website.domain() : boringProxyProperties.fqdn(website.domain());
	}

	/** This server's own public IP, as resolved via boringProxyProperties.domain() -- already guaranteed correct by check-prerequisites' startup DNS check, so no extra external lookup (e.g. an ifconfig.me call) is needed here. Null if resolution fails for some reason, in which case DNS mismatch checks are skipped rather than risking a false positive. */
	private String resolveIp(String hostname) {
		try {
			return InetAddress.getByName(hostname).getHostAddress();
		} catch (UnknownHostException e) {
			log.warn("Could not resolve {} to check Local Website DNS records against", hostname, e);
			return null;
		}
	}

	/** True when an ownDomain site's own DNS doesn't resolve to this server at all, or resolves somewhere else -- exactly what starves it of a real Let's Encrypt certificate, since the ACME HTTP-01 challenge has to reach this server at that domain. serverIp null (couldn't resolve our own domain) never reports a mismatch, to avoid a false positive. */
	private boolean hasDnsMismatch(String domain, String serverIp) {
		if (serverIp == null) {
			return false;
		}
		return !serverIp.equals(resolveIp(domain));
	}

	/** The tunnel domain for rawDomain (the LocalWebsiteStore key) as it exists right now (mode-aware via the stored record), or the default label.DOMAIN composition if we have no record for it yet. */
	private String currentFqdn(String rawDomain) {
		LocalWebsite stored = localWebsiteStore.find(rawDomain);
		return stored != null ? fqdn(stored) : boringProxyProperties.fqdn(rawDomain);
	}

	/**
	 * tlsTermination "server" -- selfieproxy-local-websites' shared NGINX only ever speaks plain
	 * HTTP (see StaticSiteProvisioner's generated server blocks), so Selfie Proxy must terminate
	 * the public TLS connection itself with a managed cert and forward plain HTTP onward, exactly
	 * like a plain-HTTP Exposed App (see TunnelMapper). "client"/passthrough mode would pipe the
	 * browser's raw TLS bytes straight to that plain-HTTP NGINX, which can't parse them.
	 */
	private CreateTunnelRequestDto toCreateTunnelRequest(String domain, String owner) {
		return new CreateTunnelRequestDto(
				domain,
				owner,
				thisServerAgentProperties.agentName(),
				sitesWebserverProperties.port(),
				sitesWebserverProperties.host(),
				null,
				null,
				null,
				null,
				null,
				"server",
				null,
				null,
				null);
	}

	/** originalDomain is null when adding, and the LocalWebsiteStore key being edited when renaming (excluded from the collision check). */
	private List<String> validate(LocalWebsite website, String originalDomain) {
		List<String> errors = new ArrayList<>();

		if (website.domain() == null || website.domain().isBlank()) {
			errors.add(website.ownDomain() ? "Domain is required." : "Subdomain is required.");
			return errors;
		}

		if (website.ownDomain()) {
			if (website.domain().chars().anyMatch(Character::isWhitespace)) {
				errors.add("Domain cannot contain a space.");
				return errors;
			}
		} else if (!DnsLabelValidator.isValid(website.domain())) {
			errors.add("Subdomain can only contain letters, numbers, and hyphens, and cannot start or end with a hyphen.");
			return errors;
		}

		String fqdn = fqdn(website);
		if (fqdn.equalsIgnoreCase(boringProxyProperties.adminDomain())
				|| fqdn.equalsIgnoreCase(boringProxyProperties.portalDomain())) {
			errors.add("\"" + website.domain() + "\" is reserved for the BoringProxy/Selfie Proxy admin portal itself.");
			return errors;
		}

		Map<String, TunnelDto> existing = boringProxyClient.listTunnels();
		boolean taken = existing.keySet().stream()
				.anyMatch(d -> d.equalsIgnoreCase(fqdn)
						&& (originalDomain == null || !d.equalsIgnoreCase(currentFqdn(originalDomain))));
		if (taken) {
			errors.add((website.ownDomain() ? "Domain \"" : "Subdomain \"") + website.domain() + "\" is already in use.");
		}

		return errors;
	}
}
