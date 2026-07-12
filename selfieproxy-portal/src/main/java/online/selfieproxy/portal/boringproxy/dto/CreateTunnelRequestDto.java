package online.selfieproxy.portal.boringproxy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors BoringProxy's CreateTunnelRequest REST shape. BoringProxy's REST API
 * decodes JSON bodies into the same kebab-case keys its form/query-param API uses
 * (see rest_api.go's parseJSONBody), so these property names must stay hyphenated
 * regardless of the global snake_case Jackson naming strategy.
 */
public record CreateTunnelRequestDto(
		String domain,
		String owner,
		@JsonProperty("agent-name") String agentName,
		@JsonProperty("client-port") Integer clientPort,
		@JsonProperty("client-addr") String clientAddr,
		@JsonProperty("tunnel-port") Integer tunnelPort,
		@JsonProperty("allow-external-tcp") Boolean allowExternalTcp,
		@JsonProperty("password-protect") Boolean passwordProtect,
		String username,
		String password,
		@JsonProperty("tls-termination") String tlsTermination,
		@JsonProperty("sso-protect") Boolean ssoProtect,
		@JsonProperty("ssh-server-addr") String sshServerAddr,
		@JsonProperty("ssh-server-port") Integer sshServerPort) {
}
