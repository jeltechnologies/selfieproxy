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
		return "local-websites";
	}

	@GetMapping("/local-websites/new")
	public String newWebsite(Model model) {
		model.addAttribute("website", new LocalWebsite(""));
		model.addAttribute("isNew", true);
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
		return "edit-local-website";
	}

	@PostMapping("/local-websites")
	public String create(@ModelAttribute LocalWebsiteForm form, HttpServletRequest request, Model model) {
		String domain = normalize(form.domain());

		List<String> errors = validate(domain, null);
		if (!errors.isEmpty()) {
			model.addAttribute("website", new LocalWebsite(domain));
			model.addAttribute("isNew", true);
			model.addAttribute("errors", errors);
			return "edit-local-website";
		}

		PortalSession session = PortalSessions.get(request.getSession(false));
		boringProxyClient.createTunnel(toCreateTunnelRequest(domain, session.owner()));
		staticSiteProvisioner.provision(domain);
		localWebsiteStore.save(new LocalWebsite(domain));
		return "redirect:/local-websites";
	}

	@PostMapping("/local-websites/{domain}")
	public String update(@PathVariable String domain, @ModelAttribute LocalWebsiteForm form,
			HttpServletRequest request, Model model) throws InterruptedException {
		String newDomain = normalize(form.domain());

		if (newDomain.equals(domain)) {
			return "redirect:/local-websites";
		}

		List<String> errors = validate(newDomain, domain);
		if (!errors.isEmpty()) {
			model.addAttribute("website", new LocalWebsite(newDomain));
			model.addAttribute("isNew", false);
			model.addAttribute("errors", errors);
			return "edit-local-website";
		}

		PortalSession session = PortalSessions.get(request.getSession(false));
		deleteTunnelIgnoringMissing(domain);
		Thread.sleep(2000);
		boringProxyClient.createTunnel(toCreateTunnelRequest(newDomain, session.owner()));
		staticSiteProvisioner.rename(domain, newDomain);
		localWebsiteStore.delete(domain);
		localWebsiteStore.save(new LocalWebsite(newDomain));
		return "redirect:/local-websites";
	}

	/** Disables routing (deletes the Tunnel and the NGINX server block) but leaves the site's content directory untouched, so re-adding the same domain later picks up where it left off. */
	@PostMapping("/local-websites/{domain}/delete")
	public String delete(@PathVariable String domain) {
		deleteTunnelIgnoringMissing(domain);
		localWebsiteStore.delete(domain);
		staticSiteProvisioner.deprovision(domain);
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

	/** originalDomain is null when adding, and the domain being edited when renaming (excluded from the collision check). */
	private List<String> validate(String domain, String originalDomain) {
		List<String> errors = new ArrayList<>();

		if (domain == null || domain.isBlank()) {
			errors.add("Domain is required.");
			return errors;
		}
		if (domain.equalsIgnoreCase(boringProxyProperties.adminDomain())
				|| domain.equalsIgnoreCase(boringProxyProperties.portalDomain())) {
			errors.add("\"" + domain + "\" is reserved for the BoringProxy/Selfie Proxy admin portal itself.");
			return errors;
		}

		Map<String, TunnelDto> existing = boringProxyClient.listTunnels();
		boolean taken = existing.keySet().stream()
				.anyMatch(d -> d.equalsIgnoreCase(domain)
						&& (originalDomain == null || !d.equalsIgnoreCase(originalDomain)));
		if (taken) {
			errors.add("\"" + domain + "\" is already in use.");
		}

		return errors;
	}
}
