package online.selfieproxy.portal.web;

import java.io.IOException;
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
import online.selfieproxy.portal.domain.DomainService;
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
	private final DomainService domainService;

	public LocalWebsiteController(BoringProxyClient boringProxyClient, LocalWebsiteStore localWebsiteStore,
			StaticSiteProvisioner staticSiteProvisioner, SitesWebserverProperties sitesWebserverProperties,
			ThisServerAgentProperties thisServerAgentProperties, BoringProxyProperties boringProxyProperties,
			DomainService domainService) {
		this.boringProxyClient = boringProxyClient;
		this.localWebsiteStore = localWebsiteStore;
		this.staticSiteProvisioner = staticSiteProvisioner;
		this.sitesWebserverProperties = sitesWebserverProperties;
		this.thisServerAgentProperties = thisServerAgentProperties;
		this.boringProxyProperties = boringProxyProperties;
		this.domainService = domainService;
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

		return "local-websites";
	}

	@GetMapping("/local-websites/new")
	public String newWebsite(Model model) {
		model.addAttribute("website", new LocalWebsite("", boringProxyProperties.primaryDomain()));
		model.addAttribute("isNew", true);
		model.addAttribute("domains", domainService.allDomains());
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
			model.addAttribute("domains", domainService.allDomains());
			return "edit-local-website";
		}

		PortalSession session = PortalSessions.get(request.getSession(false));
		String fqdn = website.fqdn();
		boringProxyClient.createTunnel(toCreateTunnelRequest(fqdn, session.owner()));
		staticSiteProvisioner.provision(fqdn);
		if (websiteZip != null && !websiteZip.isEmpty()) {
			staticSiteProvisioner.replaceContents(fqdn, websiteZip.getInputStream());
		}
		localWebsiteStore.save(website);
		return "redirect:/local-websites";
	}

	@PostMapping("/local-websites/{fqdn}")
	public String update(@PathVariable String fqdn, @ModelAttribute LocalWebsiteForm form,
			@RequestParam(value = "websiteZip", required = false) MultipartFile websiteZip,
			HttpServletRequest request, Model model) throws InterruptedException, IOException {
		LocalWebsite website = toLocalWebsite(form);
		boolean hasZip = websiteZip != null && !websiteZip.isEmpty();

		boolean fqdnUnchanged = fqdn.equals(website.fqdn());
		if (fqdnUnchanged && !hasZip) {
			return "redirect:/local-websites";
		}

		List<String> errors = validate(website, fqdn);
		if (!errors.isEmpty()) {
			model.addAttribute("website", website);
			model.addAttribute("isNew", false);
			model.addAttribute("errors", errors);
			model.addAttribute("domains", domainService.allDomains());
			return "edit-local-website";
		}

		PortalSession session = PortalSessions.get(request.getSession(false));
		String newFqdn = website.fqdn();
		if (!fqdnUnchanged) {
			deleteTunnelIgnoringMissing(fqdn);
			Thread.sleep(2000);
			boringProxyClient.createTunnel(toCreateTunnelRequest(newFqdn, session.owner()));
			staticSiteProvisioner.rename(fqdn, newFqdn);
		}
		if (hasZip) {
			staticSiteProvisioner.replaceContents(newFqdn, websiteZip.getInputStream());
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
		if (website == null) {
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

	private LocalWebsite toLocalWebsite(LocalWebsiteForm form) {
		String label = normalize(form.label());
		return new LocalWebsite(label.isBlank() ? null : label, normalize(form.domain()));
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

		return errors;
	}
}
