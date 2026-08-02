package online.selfieproxy.portal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.SitesWebserverProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;

/**
 * Reconciles boringproxy's live tunnel set against ServerStore/LocalWebsiteStore on every portal
 * startup: every tunnel currently in boringproxy is deleted, then every tunnel implied by the two
 * stores is recreated from scratch. Deliberately a full wipe-and-rebuild rather than a diff -- the
 * simplest way to guarantee boringproxy never keeps a stale tunnel around for a Server/Local
 * Website that was renamed, removed, or otherwise had its own delete-then-recreate step silently
 * fail sometime in the past (eg. a boringproxy hiccup mid-edit), at the cost of a brief reconnect
 * for every tunnel on every portal restart -- the same trade-off already accepted for a domain
 * rename's own bulk cascade (see DomainsController). Runs on {@link ApplicationReadyEvent} for the
 * same reason as AgentBootstrap: BoringProxyClient depends on boringproxy's runtime token file
 * already existing on disk. Unconditional, unlike AgentBootstrap/ThisServerBootstrap/
 * LocalWebsiteDemoBootstrap -- there's no marker file, this runs fresh on every single startup. A
 * failure deleting or creating any one tunnel is logged and skipped, never aborts the rest of the
 * reconciliation.
 */
@Component
public class TunnelReconciler {

	private static final Logger log = LoggerFactory.getLogger(TunnelReconciler.class);
	private static final String OWNER = "admin";

	private final BoringProxyClient boringProxyClient;
	private final TunnelMapper tunnelMapper;
	private final ServerStore serverStore;
	private final LocalWebsiteStore localWebsiteStore;
	private final ThisServerAgentProperties thisServerAgentProperties;
	private final SitesWebserverProperties sitesWebserverProperties;

	public TunnelReconciler(BoringProxyClient boringProxyClient, TunnelMapper tunnelMapper, ServerStore serverStore,
			LocalWebsiteStore localWebsiteStore, ThisServerAgentProperties thisServerAgentProperties,
			SitesWebserverProperties sitesWebserverProperties) {
		this.boringProxyClient = boringProxyClient;
		this.tunnelMapper = tunnelMapper;
		this.serverStore = serverStore;
		this.localWebsiteStore = localWebsiteStore;
		this.thisServerAgentProperties = thisServerAgentProperties;
		this.sitesWebserverProperties = sitesWebserverProperties;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void reconcile() {
		Map<String, TunnelDto> existing = boringProxyClient.listTunnels();
		int deleted = 0;
		for (String domain : existing.keySet()) {
			try {
				boringProxyClient.deleteTunnel(domain);
				deleted++;
			} catch (Exception e) {
				log.warn("Failed to delete tunnel '{}' during startup reconciliation: {}", domain, e.getMessage());
			}
		}

		List<CreateTunnelRequestDto> desired = desiredTunnels();
		int created = 0;
		for (CreateTunnelRequestDto request : desired) {
			try {
				boringProxyClient.createTunnel(request);
				created++;
			} catch (Exception e) {
				log.warn("Failed to recreate tunnel '{}' during startup reconciliation: {}", request.domain(), e.getMessage());
			}
		}

		log.info("Startup tunnel reconciliation: deleted {} existing tunnel(s), recreated {} of {} desired tunnel(s).",
				deleted, created, desired.size());
	}

	/** Every tunnel ServerStore/LocalWebsiteStore imply -- the same mapping ServerController/LocalWebsiteController themselves use to create tunnels on an ordinary save. */
	private List<CreateTunnelRequestDto> desiredTunnels() {
		List<CreateTunnelRequestDto> requests = new ArrayList<>();
		for (Server server : serverStore.values()) {
			tunnelMapper.tunnelPlans(server, OWNER).values().forEach(plan -> requests.add(plan.request()));
			tunnelMapper.portForwardingTunnelPlans(server, OWNER).forEach(plan -> requests.add(plan.request()));
		}
		for (LocalWebsite website : localWebsiteStore.list()) {
			requests.add(toCreateTunnelRequest(website.fqdn()));
		}
		return requests;
	}

	/** Mirrors LocalWebsiteController.toCreateTunnelRequest -- owner is hardcoded since there's no HTTP session at startup. */
	private CreateTunnelRequestDto toCreateTunnelRequest(String fqdn) {
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
}
