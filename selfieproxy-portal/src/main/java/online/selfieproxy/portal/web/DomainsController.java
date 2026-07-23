package online.selfieproxy.portal.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.BoringProxyException;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.SitesWebserverProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;
import online.selfieproxy.portal.domain.DomainRenameResult;
import online.selfieproxy.portal.domain.DomainService;
import online.selfieproxy.portal.domain.DomainStore;
import online.selfieproxy.portal.domain.DomainValidator;
import online.selfieproxy.portal.domain.ExposedApp;
import online.selfieproxy.portal.domain.ExposedAppStore;
import online.selfieproxy.portal.domain.LocalWebsite;
import online.selfieproxy.portal.domain.LocalWebsiteStore;
import online.selfieproxy.portal.domain.SecondaryDomain;
import online.selfieproxy.portal.domain.StaticSiteProvisioner;
import online.selfieproxy.portal.domain.TunnelMapper;

/**
 * The Domains settings page: lists the primary domain (fixed, never editable/removable here) plus
 * every registered secondary domain with its DNS status, and lets the admin add, rename, or remove
 * a secondary domain. Never calls a domain "secondary" in user-facing text, per the product spec --
 * that's purely this codebase's internal name for "not the primary domain".
 */
@Controller
public class DomainsController {

	private static final Logger log = LoggerFactory.getLogger(DomainsController.class);

	private static final String OWNER = "admin";
	private static final long TUNNEL_RECREATE_WAIT_MS = 2000;

	private final DomainStore domainStore;
	private final DomainService domainService;
	private final BoringProxyClient boringProxyClient;
	private final TunnelMapper tunnelMapper;
	private final ExposedAppStore exposedAppStore;
	private final LocalWebsiteStore localWebsiteStore;
	private final StaticSiteProvisioner staticSiteProvisioner;
	private final SitesWebserverProperties sitesWebserverProperties;
	private final ThisServerAgentProperties thisServerAgentProperties;

	public DomainsController(DomainStore domainStore, DomainService domainService,
			BoringProxyClient boringProxyClient, TunnelMapper tunnelMapper, ExposedAppStore exposedAppStore,
			LocalWebsiteStore localWebsiteStore, StaticSiteProvisioner staticSiteProvisioner,
			SitesWebserverProperties sitesWebserverProperties, ThisServerAgentProperties thisServerAgentProperties) {
		this.domainStore = domainStore;
		this.domainService = domainService;
		this.boringProxyClient = boringProxyClient;
		this.tunnelMapper = tunnelMapper;
		this.exposedAppStore = exposedAppStore;
		this.localWebsiteStore = localWebsiteStore;
		this.staticSiteProvisioner = staticSiteProvisioner;
		this.sitesWebserverProperties = sitesWebserverProperties;
		this.thisServerAgentProperties = thisServerAgentProperties;
	}

	@GetMapping("/domains")
	public String list(Model model) {
		List<DomainService.Domain> domains = domainService.allDomains();
		Map<String, DomainService.DomainStatus> statusByDomain = new LinkedHashMap<>();
		domains.forEach(d -> statusByDomain.put(d.name(), domainService.statusOf(d.name())));
		model.addAttribute("domains", domains);
		model.addAttribute("statusByDomain", statusByDomain);
		return "domains";
	}

	@GetMapping("/domains/new")
	public String newDomain(Model model) {
		model.addAttribute("isNew", true);
		model.addAttribute("name", "");
		return "edit-domain";
	}

	@PostMapping("/domains")
	public String create(@RequestParam String name, Model model) {
		String normalized = normalize(name);
		List<String> errors = validate(normalized, null);
		if (!errors.isEmpty()) {
			model.addAttribute("isNew", true);
			model.addAttribute("name", normalized);
			model.addAttribute("errors", errors);
			return "edit-domain";
		}
		domainStore.save(new SecondaryDomain(normalized));
		return "redirect:/domains";
	}

	@GetMapping("/domains/{name}/edit")
	public String edit(@PathVariable String name, Model model) {
		if (!domainService.exists(name) || name.equals(domainService.primaryDomain())) {
			return "redirect:/domains";
		}
		DomainService.DomainStatus status = domainService.statusOf(name);
		model.addAttribute("isNew", false);
		model.addAttribute("name", name);
		model.addAttribute("status", status);
		if (status == DomainService.DomainStatus.ERROR) {
			model.addAttribute("dnsExplanation", domainService.dnsExplanation(name));
		}
		return "edit-domain";
	}

	@PostMapping("/domains/{name}")
	public String update(@PathVariable String name, @RequestParam("name") String newName, Model model,
			RedirectAttributes redirectAttributes) {
		if (name.equals(domainService.primaryDomain())) {
			return "redirect:/domains";
		}
		String normalized = normalize(newName);
		List<String> errors = validate(normalized, name);
		if (!errors.isEmpty()) {
			model.addAttribute("isNew", false);
			model.addAttribute("name", name);
			model.addAttribute("errors", errors);
			return "edit-domain";
		}

		if (!normalized.equals(name)) {
			domainStore.delete(name);
			domainStore.save(new SecondaryDomain(normalized));
			DomainRenameResult result = renameDomain(name, normalized);
			redirectAttributes.addFlashAttribute("renameResult", result);
		}
		return "redirect:/domains";
	}

	@PostMapping("/domains/{name}/delete")
	public String delete(@PathVariable String name) {
		if (name.equals(domainService.primaryDomain())) {
			return "redirect:/domains";
		}
		domainStore.delete(name);
		return "redirect:/domains";
	}

	private String normalize(String name) {
		return name == null ? "" : name.trim().toLowerCase();
	}

	/** originalName is null when adding, and the domain being renamed when editing (excluded from the uniqueness check). */
	private List<String> validate(String name, String originalName) {
		List<String> errors = new ArrayList<>();
		if (name.isBlank()) {
			errors.add("Domain is required.");
			return errors;
		}
		if (!DomainValidator.isValid(name)) {
			errors.add("Domain is not a valid domain name.");
			return errors;
		}
		boolean unchanged = originalName != null && originalName.equals(name);
		if (!unchanged && domainService.exists(name)) {
			errors.add("The domain \"" + name + "\" already exists.");
		}
		return errors;
	}

	/**
	 * Every Exposed App/Local Website on oldName gets its tunnel recreated under newName (the same
	 * delete-tunnel-then-recreate-with-a-2s-wait pattern an ordinary edit already uses, just applied
	 * in bulk) -- brief downtime for the affected apps/sites, acceptable since this is a deliberate
	 * admin action. A failure on one item is recorded and never blocks the rest of the rename;
	 * deleting a domain has no such cascade at all (see delete() above), only renaming does.
	 */
	private DomainRenameResult renameDomain(String oldName, String newName) {
		List<String> failures = new ArrayList<>();
		int appsUpdated = 0;
		int sitesUpdated = 0;

		Map<String, TunnelDto> tunnels = boringProxyClient.listTunnels();
		List<ExposedApp> affectedApps = tunnels.values().stream()
				.filter(tunnel -> !thisServerAgentProperties.agentName().equals(tunnel.agentName()))
				.map(tunnelMapper::toExposedApp)
				.map(exposedAppStore::reconcile)
				.filter(app -> oldName.equals(app.domain()))
				.toList();
		for (ExposedApp app : affectedApps) {
			String oldFqdn = app.fqdn();
			try {
				ExposedApp renamed = new ExposedApp(app.subdomain(), app.name(), app.homelabName(), app.type(),
						app.protocol(), app.host(), app.port(), app.exposedPort(), app.tlsMode(), app.ssoProtected(),
						newName, app.mode(), app.username(), app.encryptedSecret(),
						app.ignoreCertificate());
				deleteTunnelIgnoringMissing(oldFqdn);
				sleep();
				boringProxyClient.createTunnel(tunnelMapper.toCreateTunnelRequest(renamed, OWNER));
				exposedAppStore.delete(oldFqdn);
				exposedAppStore.save(renamed);
				appsUpdated++;
			} catch (Exception e) {
				failures.add("Application " + oldFqdn + ": " + e.getMessage());
			}
		}

		List<LocalWebsite> affectedSites = localWebsiteStore.list().stream()
				.filter(site -> oldName.equals(site.domain()))
				.toList();
		for (LocalWebsite site : affectedSites) {
			String oldFqdn = site.fqdn();
			try {
				LocalWebsite renamed = new LocalWebsite(site.label(), newName, site.redirectTo(), site.demo());
				String newFqdn = renamed.fqdn();
				deleteTunnelIgnoringMissing(oldFqdn);
				sleep();
				boringProxyClient.createTunnel(toLocalWebsiteTunnelRequest(newFqdn));
				staticSiteProvisioner.rename(oldFqdn, newFqdn, site.redirectTo());
				localWebsiteStore.delete(oldFqdn);
				localWebsiteStore.save(renamed);
				sitesUpdated++;
			} catch (Exception e) {
				failures.add("Local website " + oldFqdn + ": " + e.getMessage());
			}
		}

		return new DomainRenameResult(appsUpdated, sitesUpdated, failures);
	}

	/** Same shape as LocalWebsiteController/BackupService's own private toCreateTunnelRequest -- Local Websites always point at the shared selfieproxy-local-websites container through the hidden "This Server" homelab. */
	private CreateTunnelRequestDto toLocalWebsiteTunnelRequest(String fqdn) {
		return new CreateTunnelRequestDto(
				fqdn,
				OWNER,
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

	/** The Tunnel record can go stale (eg. already removed from boringproxy some other way); don't let that block the rest of the rename, mirrors ExposedAppController/LocalWebsiteController/BackupService's own identical helper. */
	private void deleteTunnelIgnoringMissing(String fqdn) {
		try {
			boringProxyClient.deleteTunnel(fqdn);
		} catch (BoringProxyException e) {
			if (!"Tunnel doesn't exist".equals(e.getMessage())) {
				throw e;
			}
			log.warn("Tunnel {} was already gone from boringproxy; continuing domain rename", fqdn);
		}
	}

	private void sleep() {
		try {
			Thread.sleep(TUNNEL_RECREATE_WAIT_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for tunnel teardown", e);
		}
	}
}
