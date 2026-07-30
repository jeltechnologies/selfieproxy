package online.selfieproxy.portal.domain;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.domain.PrerequisitesCheckResult.CheckLine;
import online.selfieproxy.portal.domain.PrerequisitesCheckResult.Severity;
import online.selfieproxy.portal.portscan.PortScanClient;

/**
 * Re-implements what the standalone check-prerequisites container used to do at deploy time --
 * confirm PRIMARY_DOMAIN/*.PRIMARY_DOMAIN's DNS points at this server and that ports 80/443/22 are
 * reachable from the internet -- but as a portal feature instead of a blocking pre-flight gate.
 * Runs once automatically on {@link ApplicationReadyEvent} (same trigger as AgentBootstrap), and
 * again on demand via the dashboard's "Recheck now" button (see DashboardController). Unlike
 * AgentBootstrap/LocalWebsiteDemoBootstrap this is never marker-gated -- it must re-run every
 * restart, since the point is to catch a still-misconfigured firewall on every boot, not just once
 * ever. Never blocks or crashes portal startup: any failure becomes a WARN line, not an exception.
 * No temporary listener bind is needed here (unlike check-prerequisites.sh) since this runs well
 * after selfieproxy-reverseproxy already holds real 80/443 sockets -- see root CLAUDE.md's
 * startup-order section.
 */
@Component
public class PrerequisitesCheckService {

	private static final Logger log = LoggerFactory.getLogger(PrerequisitesCheckService.class);

	private final DomainService domainService;
	private final PortScanClient portScanClient;
	private final BoringProxyProperties properties;
	private final RestClient publicIpClient;

	private volatile PrerequisitesCheckResult current;

	public PrerequisitesCheckService(DomainService domainService, PortScanClient portScanClient,
			BoringProxyProperties properties) {
		this.domainService = domainService;
		this.portScanClient = portScanClient;
		this.properties = properties;
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(10));
		this.publicIpClient = RestClient.builder().requestFactory(factory).build();
	}

	/** Null until the first check (triggered by ApplicationReadyEvent) completes. */
	public PrerequisitesCheckResult current() {
		return current;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void checkOnStartup() {
		recheck();
	}

	/** Runs synchronously (up to ~45s, same as check-prerequisites.sh's own poll budget) -- fine for a boot-time listener or a deliberate admin click, never called from a hot path. */
	public synchronized PrerequisitesCheckResult recheck() {
		List<CheckLine> lines = new ArrayList<>();
		String publicIp = resolvePublicIp(lines);
		if (publicIp != null) {
			checkDomain(properties.primaryDomain(), publicIp, lines);
			checkDomain("*." + properties.primaryDomain(), publicIp, lines);
		}
		checkPorts(publicIp, lines);

		PrerequisitesCheckResult result = new PrerequisitesCheckResult(Instant.now(), List.copyOf(lines));
		current = result;
		return result;
	}

	private String resolvePublicIp(List<CheckLine> lines) {
		try {
			String ip = publicIpClient.get().uri(URI.create("https://ifconfig.me")).retrieve().body(String.class);
			if (ip == null || ip.isBlank()) {
				lines.add(new CheckLine(Severity.WARN,
						"Could not determine this server's public IP; skipping the DNS and port checks."));
				return null;
			}
			return ip.trim();
		} catch (Exception e) {
			log.warn("Could not determine public IP for prerequisites check: {}", e.getMessage());
			lines.add(new CheckLine(Severity.WARN,
					"Could not determine this server's public IP; skipping the DNS and port checks."));
			return null;
		}
	}

	private void checkDomain(String name, String publicIp, List<CheckLine> lines) {
		String resolved = domainService.resolveIp(name);
		if (resolved == null) {
			lines.add(new CheckLine(Severity.ERROR,
					name + " does not resolve to " + publicIp + ". Fix its DNS records."));
		} else if (!resolved.equals(publicIp)) {
			lines.add(new CheckLine(Severity.ERROR,
					name + " resolves to " + resolved + ", expected " + publicIp + ". Fix its DNS records."));
		} else {
			lines.add(new CheckLine(Severity.OK, name + " -> " + resolved));
		}
	}

	private void checkPorts(String publicIp, List<CheckLine> lines) {
		Optional<Set<Integer>> openPorts = portScanClient.openPorts();
		if (openPorts.isEmpty()) {
			lines.add(new CheckLine(Severity.WARN,
					"Could not verify port reachability via external scan (api.portscan.com unreachable, rate-limited, or timed out). "
							+ "Double check that inbound 80/tcp, 443/tcp (and 22/tcp, unless STEALTH_MODE is enabled) are actually open in the server's firewall."));
			return;
		}
		Set<Integer> open = openPorts.get();
		checkPort(80, Severity.ERROR, open, publicIp, lines);
		checkPort(443, Severity.ERROR, open, publicIp, lines);
		if (properties.stealthMode()) {
			lines.add(new CheckLine(Severity.OK,
					"STEALTH_MODE is enabled, skipping the port 22 check (agent SSH tunnels over 443 instead)."));
		} else {
			checkPort(22, Severity.WARN, open, publicIp, lines);
		}
	}

	private void checkPort(int port, Severity notOpenSeverity, Set<Integer> open, String publicIp,
			List<CheckLine> lines) {
		if (open.contains(port)) {
			lines.add(new CheckLine(Severity.OK,
					"Port " + port + "/tcp is reachable at " + publicIp + " (confirmed by external scan)."));
			return;
		}
		String message = "Port " + port + "/tcp is not reachable at " + publicIp + " (confirmed by external scan). "
				+ (port == 22
						? "Homelab agents cannot connect until inbound 22/tcp is open (or enable STEALTH_MODE to tunnel agent SSH over 443 instead)."
						: "Open inbound " + port + "/tcp in the server's firewall.");
		lines.add(new CheckLine(notOpenSeverity, message));
	}
}
