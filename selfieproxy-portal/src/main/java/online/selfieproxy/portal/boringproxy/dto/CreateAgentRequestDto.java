package online.selfieproxy.portal.boringproxy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors BoringProxy's CreateAgentRequest REST shape (POST /agents). */
public record CreateAgentRequestDto(
		String owner,
		@JsonProperty("agent-name") String agentName) {
}
