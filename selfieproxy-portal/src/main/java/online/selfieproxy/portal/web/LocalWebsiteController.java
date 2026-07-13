package online.selfieproxy.portal.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.BoringProxyException;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.SitesWebserverProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;
import online.selfieproxy.portal.domain.LocalWebsite;
import online.selfieproxy.portal.domain.LocalWebsiteStore;
import online.selfieproxy.portal.domain.StaticSiteProvisioner;
import online.selfieproxy.portal.session.PortalSession;
import online.selfieproxy.portal.session.PortalSessions;

import jakarta.servlet.http.HttpServletRequest;

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
		model.addAttribute("websites", localWebsiteStore.list());
		model.addAttribute("boringProxyProperties", boringProxyProperties);
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
	public String create(@ModelAttribute LocalWebsiteForm form, HttpServletRequest request, Model model) {
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
		localWebsiteStore.save(website);
		return "redirect:/local-websites";
	}

	@PostMapping("/local-websites/{domain}")
	public String update(@PathVariable String domain, @ModelAttribute LocalWebsiteForm form,
			HttpServletRequest request, Model model) throws InterruptedException {
		LocalWebsite website = toLocalWebsite(form);

		LocalWebsite old = localWebsiteStore.find(domain);
		boolean unchanged = old != null && old.domain().equals(website.domain()) && old.ownDomain() == website.ownDomain();
		if (unchanged) {
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
		deleteTunnelIgnoringMissing(oldFqdn);
		Thread.sleep(2000);
		boringProxyClient.createTunnel(toCreateTunnelRequest(newFqdn, session.owner()));
		staticSiteProvisioner.rename(oldFqdn, newFqdn);
		if (!domain.equals(website.domain())) {
			localWebsiteStore.delete(domain);
		}
		localWebsiteStore.save(website);
		return "redirect:/local-websites";
	}

	/** Disables routing (deletes the Tunnel and the NGINX server block) but leaves the site's content directory untouched, so re-adding the same domain later picks up where it left off. */
	@PostMapping("/local-websites/{domain}/delete")
	public String delete(@PathVariable String domain) {
		String fqdn = currentFqdn(domain);
		deleteTunnelIgnoringMissing(fqdn);
		localWebsiteStore.delete(domain);
		staticSiteProvisioner.deprovision(fqdn);
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

	/** The tunnel domain for rawDomain (the LocalWebsiteStore key) as it exists right now (mode-aware via the stored record), or the default label.DOMAIN composition if we have no record for it yet. */
	private String currentFqdn(String rawDomain) {
		LocalWebsite stored = localWebsiteStore.find(rawDomain);
		return stored != null ? fqdn(stored) : boringProxyProperties.fqdn(rawDomain);
	}

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
				"client",
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
