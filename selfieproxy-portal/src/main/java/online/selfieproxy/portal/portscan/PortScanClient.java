package online.selfieproxy.portal.portscan;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import online.selfieproxy.portal.portscan.dto.PortScanStartResponseDto;
import online.selfieproxy.portal.portscan.dto.PortScanStatusResponseDto;

/**
 * Client for api.portscan.com's free "fast" scan API -- the only reliable way to confirm a port
 * is actually reachable from the internet, since a same-host self-dial to this server's own
 * public IP is routed via the local/loopback path regardless of source address (Linux routes
 * traffic addressed to a locally-assigned IP via RTN_LOCAL, and ufw's default rules unconditionally
 * accept everything on lo). Scans whichever IP the request originates from, no key or params
 * needed. Mirrors the POST-then-poll loop check-prerequisites.sh used to run at container startup,
 * before that check moved into PrerequisitesCheckService.
 */
@Component
public class PortScanClient {

	private static final Logger log = LoggerFactory.getLogger(PortScanClient.class);
	private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
	private static final Duration POLL_TIMEOUT = Duration.ofSeconds(45);
	private static final int DEFAULT_ETA_SECONDS = 8;

	private final RestClient restClient;

	public PortScanClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(10));
		this.restClient = RestClient.builder()
				.baseUrl("https://api.portscan.com/v1/fast")
				.requestFactory(factory)
				.build();
	}

	/**
	 * Empty when the scan itself couldn't be started or didn't complete in time
	 * (api.portscan.com unreachable, rate-limited, or timed out) -- not evidence of anything about
	 * the ports themselves, per check-prerequisites.sh's own fallback behavior.
	 */
	public Optional<Set<Integer>> openPorts() {
		PortScanStartResponseDto start;
		try {
			start = restClient.post().retrieve().body(PortScanStartResponseDto.class);
		} catch (Exception e) {
			log.warn("Could not start port scan via api.portscan.com: {}", e.getMessage());
			return Optional.empty();
		}

		int etaSeconds = start != null && start.etaSeconds() != null ? start.etaSeconds() : DEFAULT_ETA_SECONDS;
		sleep(Duration.ofSeconds(etaSeconds));

		Duration waited = Duration.ZERO;
		while (waited.compareTo(POLL_TIMEOUT) < 0) {
			PortScanStatusResponseDto status;
			try {
				status = restClient.get().retrieve().body(PortScanStatusResponseDto.class);
			} catch (Exception e) {
				log.warn("Port scan poll against api.portscan.com failed: {}", e.getMessage());
				return Optional.empty();
			}
			if (status != null && "complete".equals(status.status())) {
				List<PortScanStatusResponseDto.OpenPortDto> open =
						status.portsOpen() != null ? status.portsOpen() : List.of();
				return Optional.of(open.stream()
						.map(PortScanStatusResponseDto.OpenPortDto::port)
						.collect(Collectors.toSet()));
			}
			sleep(POLL_INTERVAL);
			waited = waited.plus(POLL_INTERVAL);
		}
		log.warn("Port scan against api.portscan.com did not complete within {}s", POLL_TIMEOUT.getSeconds());
		return Optional.empty();
	}

	private void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for port scan", e);
		}
	}
}
