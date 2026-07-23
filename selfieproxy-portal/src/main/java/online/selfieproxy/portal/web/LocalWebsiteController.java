package online.selfieproxy.portal.web;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
import online.selfieproxy.portal.domain.DomainService;
import online.selfieproxy.portal.domain.LocalWebsite;
import online.selfieproxy.portal.domain.LocalWebsiteDemoStatus;
import online.selfieproxy.portal.domain.LocalWebsiteStore;
import online.selfieproxy.portal.domain.LocalWebsiteType;
import online.selfieproxy.portal.domain.RedirectUrlValidator;
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
	private final DomainService domainService;
	private final LocalWebsiteDemoStatus localWebsiteDemoStatus;

	public LocalWebsiteController(BoringProxyClient boringProxyClient, LocalWebsiteStore localWebsiteStore,
			StaticSiteProvisioner staticSiteProvisioner, SitesWebserverProperties sitesWebserverProperties,
			ThisServerAgentProperties thisServerAgentProperties, BoringProxyProperties boringProxyProperties,
			DomainService domainService, LocalWebsiteDemoStatus localWebsiteDemoStatus) {
		this.boringProxyClient = boringProxyClient;
		this.localWebsiteStore = localWebsiteStore;
		this.staticSiteProvisioner = staticSiteProvisioner;
		this.sitesWebserverProperties = sitesWebserverProperties;
		this.thisServerAgentProperties = thisServerAgentProperties;
		this.boringProxyProperties = boringProxyProperties;
		this.domainService = domainService;
		this.localWebsiteDemoStatus = localWebsiteDemoStatus;
	}

	@GetMapping("/local-websites")
	public String list(Model model) {
		List<LocalWebsite> websites = localWebsiteStore.list();
		model.addAttribute("websites", websites);
		model.addAttribute("domainService", domainService);
		model.addAttribute("domains", domainService.allDomains());

		// Domains still waiting on a Let's Encrypt certificate (e.g. after hitting a rate limit) --
		// boringproxy serves those over a temporary self-signed certificate in the meantime and
		// keeps retrying in the background, see selfieproxy-reverseproxy's TunnelManager. Scoped to
		// This Server's own tunnels (the opposite filter from DashboardController's Applications
		// page), since a Local Website's tunnel always belongs to the hidden "This Server" homelab.
		// The per-row badge stays alongside the row's own domain warning icon.
		Map<String, Boolean> certPendingByDomain = boringProxyClient.listTunnels().values().stream()
				.filter(tunnel -> thisServerAgentProperties.agentName().equals(tunnel.agentName()))
				.collect(Collectors.toMap(TunnelDto::domain, TunnelDto::certPending));
		model.addAttribute("certPendingByDomain", certPendingByDomain);
		boolean hasPendingCerts = certPendingByDomain.values().stream().anyMatch(Boolean::booleanValue);
		model.addAttribute("hasPendingCerts", hasPendingCerts);

		// Only shown while the demo content site LocalWebsiteDemoBootstrap created on first boot is
		// still byte-for-byte the bundled demo -- disappears the moment it's replaced with real
		// content, regardless of whether that happened through this portal or directly on disk.
		boolean demoContentAvailable = localWebsiteDemoStatus.isDemoContentUnmodified();
		model.addAttribute("demoContentAvailable", demoContentAvailable);
		if (demoContentAvailable) {
			model.addAttribute("demoContentFqdn", localWebsiteDemoStatus.demoContentFqdn());
			model.addAttribute("demoRedirectAvailable", localWebsiteDemoStatus.isDemoRedirectUnmodified());
			model.addAttribute("demoRedirectDomain", localWebsiteDemoStatus.demoRedirectDomain());
		}

		return "local-websites";
	}

	@GetMapping("/local-websites/new")
	public String newWebsite(Model model) {
		LocalWebsite website = new LocalWebsite("", boringProxyProperties.primaryDomain(), null, false);
		model.addAttribute("website", website);
		model.addAttribute("isNew", true);
		model.addAttribute("domains", domainService.allDomains());
		addRedirectTargetAttributes(model, website, null);
		return "edit-local-website";
	}

	@GetMapping("/local-websites/{fqdn}/edit")
	public String editWebsite(@PathVariable String fqdn, Model model) {
		LocalWebsite website = localWebsiteStore.find(fqdn);
		if (website == null) {
			return "redirect:/local-websites";
		}
		model.addAttribute("website", website);
		model.addAttribute("isNew", false);
		model.addAttribute("domains", domainService.allDomains());
		addRedirectTargetAttributes(model, website, fqdn);
		return "edit-local-website";
	}

	@PostMapping("/local-websites")
	public String create(@ModelAttribute LocalWebsiteForm form,
			@RequestParam(value = "websiteZip", required = false) MultipartFile websiteZip,
			HttpServletRequest request, Model model) throws IOException {
		LocalWebsite website = toLocalWebsite(form, null);

		List<String> errors = validate(website, null);
		if (!errors.isEmpty()) {
			model.addAttribute("website", website);
			model.addAttribute("isNew", true);
			model.addAttribute("errors", errors);
			model.addAttribute("domains", domainService.allDomains());
			addRedirectTargetAttributes(model, website, null);
			return "edit-local-website";
		}

		PortalSession session = PortalSessions.get(request.getSession(false));
		String fqdn = website.fqdn();
		boringProxyClient.createTunnel(toCreateTunnelRequest(fqdn, session.owner()));
		staticSiteProvisioner.provision(fqdn, website.redirectTo());
		if (websiteZip != null && !websiteZip.isEmpty() && !website.isRedirect()) {
			staticSiteProvisioner.replaceContents(fqdn, websiteZip.getInputStream());
		}
		localWebsiteStore.save(website);
		return "redirect:/local-websites";
	}

	@PostMapping("/local-websites/{fqdn}")
	public String update(@PathVariable String fqdn, @ModelAttribute LocalWebsiteForm form,
			@RequestParam(value = "websiteZip", required = false) MultipartFile websiteZip,
			HttpServletRequest request, Model model) throws InterruptedException, IOException {
		LocalWebsite existing = localWebsiteStore.find(fqdn);
		LocalWebsite website = toLocalWebsite(form, existing);
		boolean hasZip = websiteZip != null && !websiteZip.isEmpty();

		boolean fqdnUnchanged = fqdn.equals(website.fqdn());
		// redirectTo is independent of the fqdn -- "fqdn unchanged and no zip" alone no longer means
		// nothing changed, since the redirect target/mode could have.
		boolean redirectUnchanged = existing != null && Objects.equals(existing.redirectTo(), website.redirectTo());
		if (fqdnUnchanged && redirectUnchanged && !hasZip) {
			return "redirect:/local-websites";
		}

		List<String> errors = validate(website, fqdn);
		if (!errors.isEmpty()) {
			model.addAttribute("website", website);
			model.addAttribute("isNew", false);
			model.addAttribute("errors", errors);
			model.addAttribute("domains", domainService.allDomains());
			addRedirectTargetAttributes(model, website, fqdn);
			return "edit-local-website";
		}

		PortalSession session = PortalSessions.get(request.getSession(false));
		String newFqdn = website.fqdn();
		if (!fqdnUnchanged) {
			deleteTunnelIgnoringMissing(fqdn);
			Thread.sleep(2000);
			boringProxyClient.createTunnel(toCreateTunnelRequest(newFqdn, session.owner()));
			staticSiteProvisioner.rename(fqdn, newFqdn, website.redirectTo());
		} else if (!redirectUnchanged) {
			// Same domain, same tunnel -- only the NGINX block needs rewriting.
			staticSiteProvisioner.provision(newFqdn, website.redirectTo());
		}
		if (hasZip && !website.isRedirect()) {
			staticSiteProvisioner.replaceContents(newFqdn, websiteZip.getInputStream());
		}
		if ((hasZip && !website.isRedirect()) || !redirectUnchanged) {
			// A freshly uploaded ZIP, or a changed redirect target/mode, means this can no longer be
			// the untouched bootstrap demo, even if it started out as one -- see
			// LocalWebsite.demo()/LocalWebsiteDemoStatus. A rename alone (fqdn change with the same
			// redirectTo) still carries the flag forward unchanged.
			website = new LocalWebsite(website.label(), website.domain(), website.redirectTo(), false);
		}
		if (!fqdnUnchanged) {
			localWebsiteStore.delete(fqdn);
		}
		localWebsiteStore.save(website);
		return "redirect:/local-websites";
	}

	@GetMapping("/local-websites/{fqdn}/download")
	public void download(@PathVariable String fqdn, HttpServletResponse response) throws IOException {
		LocalWebsite website = localWebsiteStore.find(fqdn);
		if (website == null || website.isRedirect()) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		String safeFileName = fqdn.replaceAll("[^A-Za-z0-9.-]", "_");
		response.setContentType("application/zip");
		response.setHeader("Content-Disposition", "attachment; filename=\"" + safeFileName + ".zip\"");
		staticSiteProvisioner.writeZip(fqdn, response.getOutputStream());
	}

	/** Removes the Tunnel, the NGINX server block, and permanently deletes the site's content directory -- nothing is kept. */
	@PostMapping("/local-websites/{fqdn}/delete")
	public String delete(@PathVariable String fqdn) {
		deleteTunnelIgnoringMissing(fqdn);
		localWebsiteStore.delete(fqdn);
		staticSiteProvisioner.remove(fqdn);
		return "redirect:/local-websites";
	}

	/** The Tunnel record can go stale (eg. already removed from boringproxy some other way) while the LocalWebsite record remains; don't let that block cleanup of the rest. */
	private void deleteTunnelIgnoringMissing(String fqdn) {
		try {
			boringProxyClient.deleteTunnel(fqdn);
		} catch (BoringProxyException e) {
			if (!"Tunnel doesn't exist".equals(e.getMessage())) {
				throw e;
			}
			log.warn("Tunnel for local website {} was already gone from boringproxy; continuing cleanup", fqdn);
		}
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase();
	}

	/**
	 * FQDNs of every deployed Web Application (Exposed App, not Network Service -- those have no
	 * public web address to redirect to) and Local Website, offered as quick-pick redirect targets
	 * on the edit page alongside the free-text address field. excludeFqdn is the site being edited
	 * (null when adding), so a site never offers itself as a target.
	 */
	private List<String> redirectTargets(String excludeFqdn) {
		Set<String> targets = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		boringProxyClient.listTunnels().values().stream()
				.filter(t -> !"passthrough".equals(t.tlsTermination()))
				.filter(t -> !thisServerAgentProperties.agentName().equals(t.agentName()))
				.map(TunnelDto::domain)
				.forEach(targets::add);
		localWebsiteStore.list().stream().map(LocalWebsite::fqdn).forEach(targets::add);
		if (excludeFqdn != null) {
			targets.removeIf(t -> t.equalsIgnoreCase(excludeFqdn));
		}
		return new ArrayList<>(targets);
	}

	/** redirectMatchesExisting drives which of the edit page's two redirect-target radios starts checked -- see edit-local-website.html. */
	private void addRedirectTargetAttributes(Model model, LocalWebsite website, String excludeFqdn) {
		List<String> targets = redirectTargets(excludeFqdn);
		model.addAttribute("redirectTargets", targets);
		model.addAttribute("redirectMatchesExisting", website.isRedirect()
				&& targets.stream().anyMatch(t -> ("https://" + t).equalsIgnoreCase(website.redirectTo())));
	}

	/** existing is the pre-edit record (null when adding); demo is carried forward here and cleared afterward in update() if the redirect target/mode actually changed -- see LocalWebsite.demo(). */
	private LocalWebsite toLocalWebsite(LocalWebsiteForm form, LocalWebsite existing) {
		String label = normalize(form.label());
		String redirectTo = form.type() == LocalWebsiteType.REDIRECT ? blankToNull(form.redirectTo()) : null;
		boolean demo = existing != null && existing.demo();
		return new LocalWebsite(label.isBlank() ? null : label, normalize(form.domain()), redirectTo, demo);
	}

	private static String blankToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		// Strips a trailing slash so StaticSiteProvisioner's "<redirectTo>$request_uri" concatenation
		// never produces a double slash -- $request_uri already starts with one.
		if (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed.isBlank() ? null : trimmed;
	}

	/**
	 * tlsTermination "server" -- selfieproxy-local-websites' shared NGINX only ever speaks plain
	 * HTTP (see StaticSiteProvisioner's generated server blocks), so Selfie Proxy must terminate
	 * the public TLS connection itself with a managed cert and forward plain HTTP onward, exactly
	 * like a plain-HTTP Exposed App (see TunnelMapper). "client"/passthrough mode would pipe the
	 * browser's raw TLS bytes straight to that plain-HTTP NGINX, which can't parse them.
	 */
	private CreateTunnelRequestDto toCreateTunnelRequest(String fqdn, String owner) {
		return new CreateTunnelRequestDto(
				fqdn,
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

	/** originalFqdn is null when adding, and the LocalWebsiteStore key being edited when renaming (excluded from the collision check). */
	private List<String> validate(LocalWebsite website, String originalFqdn) {
		List<String> errors = new ArrayList<>();

		if (website.label() != null && !website.label().isBlank() && !DnsLabelValidator.isValid(website.label())) {
			errors.add("Subdomain can only contain letters, numbers, and hyphens, and cannot start or end with a hyphen.");
			return errors;
		}
		if (!domainService.exists(website.domain())) {
			errors.add("Unknown domain.");
			return errors;
		}

		String fqdn = website.fqdn();
		if (fqdn.equalsIgnoreCase(boringProxyProperties.adminDomain())
				|| fqdn.equalsIgnoreCase(boringProxyProperties.portalDomain())) {
			errors.add("\"" + fqdn + "\" is reserved for the BoringProxy/Selfie Proxy admin portal itself.");
			return errors;
		}

		Map<String, TunnelDto> existing = boringProxyClient.listTunnels();
		boolean taken = existing.keySet().stream()
				.anyMatch(d -> d.equalsIgnoreCase(fqdn)
						&& (originalFqdn == null || !d.equalsIgnoreCase(originalFqdn)));
		if (taken) {
			errors.add(website.label() != null && !website.label().isBlank()
					? "Subdomain \"" + website.label() + "\" is already in use."
					: "\"" + website.domain() + "\" is already in use.");
		}

		if (website.isRedirect()) {
			if (!RedirectUrlValidator.isValid(website.redirectTo())) {
				errors.add("Redirect target must be an address like https://example.com, with no path, query, or fragment.");
			} else {
				String targetHost = URI.create(website.redirectTo()).getHost();
				if (targetHost != null && targetHost.equalsIgnoreCase(fqdn)) {
					errors.add("A local website can't redirect to itself.");
				}
			}
		}

		return errors;
	}
}
