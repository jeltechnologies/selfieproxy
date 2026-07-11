package online.selfieproxy.portal.boringproxy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors BoringProxy's per-agent REST response shape. lastSeen is the
 * timestamp of the agent's most recent GET /api/tunnels poll (its
 * connection heartbeat -- see Api.handleTunnels in the boringproxy fork),
 * null if the agent has never polled since the server last started.
 */
public record AgentStatusDto(@JsonProperty("last_seen") String lastSeen) {
}
