package online.selfieproxy.portal.domain;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;

/**
 * Reconciles boringproxy's live tunnel set against ServerStore/LocalWebsiteStore on every portal
 * startup: every tunnel currently in boringproxy is deleted, then every tunnel implied by the two
 * stores is recreated from scratch by {@link TunnelRepairService}. Deliberately a full
 * wipe-and-rebuild rather than a diff -- the simplest way to guarantee boringproxy never keeps a
 * stale tunnel around for a Server/Local Website that was renamed, removed, or otherwise had its
 * own delete-then-recreate step silently fail sometime in the past (eg. a boringproxy hiccup
 * mid-edit), at the cost of a brief reconnect for every tunnel on every portal restart -- the same
 * trade-off already accepted for a domain rename's own bulk cascade (see DomainsController). Runs
 * on {@link ApplicationReadyEvent} for the same reason as AgentBootstrap: BoringProxyClient depends
 * on boringproxy's runtime token file already existing on disk. Unconditional, unlike
 * AgentBootstrap/ThisServerBootstrap/LocalWebsiteDemoBootstrap -- there's no marker file, this runs
 * fresh on every single startup. A failure deleting or creating any one tunnel is logged and
 * skipped, never aborts the rest of the reconciliation; whatever it failed to recreate is picked up
 * by TunnelRepairService's periodic sweep instead of waiting for the next restart.
 */
@Component
public class TunnelReconciler {

	private static final Logger log = LoggerFactory.getLogger(TunnelReconciler.class);

	private final BoringProxyClient boringProxyClient;
	private final TunnelRepairService tunnelRepairService;

	public TunnelReconciler(BoringProxyClient boringProxyClient, TunnelRepairService tunnelRepairService) {
		this.boringProxyClient = boringProxyClient;
		this.tunnelRepairService = tunnelRepairService;
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

		int created = tunnelRepairService.createMissing();

		log.info("Startup tunnel reconciliation: deleted {} existing tunnel(s), recreated {}.", deleted, created);
	}
}
