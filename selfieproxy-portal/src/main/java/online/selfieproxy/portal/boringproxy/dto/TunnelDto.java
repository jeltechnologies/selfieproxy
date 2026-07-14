package online.selfieproxy.portal.boringproxy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors BoringProxy's Tunnel REST response shape. Like CreateTunnelRequestDto,
 * BoringProxy's REST API always uses snake_case JSON keys for this type
 * regardless of the global naming strategy, so these must be explicit.
 */
public record TunnelDto(
		String domain,
		@JsonProperty("server_address") String serverAddress,
		@JsonProperty("server_port") int serverPort,
		@JsonProperty("server_public_key") String serverPublicKey,
		String username,
		@JsonProperty("tunnel_port") int tunnelPort,
		@JsonProperty("tunnel_private_key") String tunnelPrivateKey,
		@JsonProperty("client_address") String clientAddress,
		@JsonProperty("client_port") int clientPort,
		@JsonProperty("allow_external_tcp") boolean allowExternalTcp,
		@JsonProperty("tls_termination") String tlsTermination,
		@JsonProperty("sso_protected") boolean ssoProtected,
		@JsonProperty("cert_pending") boolean certPending,
		String owner,
		@JsonProperty("agent_name") String agentName,
		@JsonProperty("auth_username") String authUsername,
		@JsonProperty("auth_password") String authPassword) {
}
