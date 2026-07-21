package online.selfieproxy.portal.boringproxy;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import online.selfieproxy.portal.boringproxy.dto.AgentStatusDto;

/**
 * Whether an agent is currently connected, shared between the Homelabs page
 * (agents.html/AgentController) and the Applications page (dashboard.html/DashboardController).
 */
@Component
public class AgentStatusService {

	/**
	 * An agent polls GET /api/tunnels every -poll-interval-ms (2000ms by
	 * default) and boringproxy records that as its last-seen heartbeat.
	 * Anything older than this is considered offline -- 2.5x the poll
	 * interval, enough to absorb one missed poll plus jitter/latency
	 * without flapping, while still surfacing a real disconnect quickly.
	 */
	private static final Duration ONLINE_THRESHOLD = Duration.ofSeconds(5);

	private final BoringProxyClient boringProxyClient;

	public AgentStatusService(BoringProxyClient boringProxyClient) {
		this.boringProxyClient = boringProxyClient;
	}

	public boolean isOnline(String agentName) {
		return isOnline(boringProxyClient.listAgents().get(agentName));
	}

	public Map<String, Boolean> onlineByAgentName() {
		return boringProxyClient.listAgents().entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, e -> isOnline(e.getValue())));
	}

	public boolean isOnline(AgentStatusDto status) {
		if (status == null || status.lastSeen() == null) {
			return false;
		}
		try {
			Instant lastSeen = Instant.parse(status.lastSeen());
			return lastSeen.isAfter(Instant.now().minus(ONLINE_THRESHOLD));
		} catch (DateTimeParseException e) {
			return false;
		}
	}
}
