package online.selfieproxy.portal.boringproxy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors BoringProxy's SetTunnelAgent REST shape (PATCH /tunnels/{domain}). Re-points a tunnel at a different agent name without touching its connection details. */
public record RenameTunnelAgentRequestDto(@JsonProperty("agent-name") String agentName) {
}
