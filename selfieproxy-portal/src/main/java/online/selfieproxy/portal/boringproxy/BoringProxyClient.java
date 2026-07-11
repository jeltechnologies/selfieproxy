package online.selfieproxy.portal.boringproxy;

import java.util.Map;
import java.util.function.Function;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import online.selfieproxy.portal.boringproxy.dto.AgentStatusDto;
import online.selfieproxy.portal.boringproxy.dto.ApiErrorDto;
import online.selfieproxy.portal.boringproxy.dto.CreateAgentRequestDto;
import online.selfieproxy.portal.boringproxy.dto.CreateTokenRequestDto;
import online.selfieproxy.portal.boringproxy.dto.CreateTokenResponseDto;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.RenameTokenAgentRequestDto;
import online.selfieproxy.portal.boringproxy.dto.RenameTunnelAgentRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TokenDataDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.BoringProxyProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Thin wrapper around BoringProxy's own REST API, mounted at /rest on the
 * admin domain -- internal only, never advertised or documented for outside
 * consumers. Every call is authenticated with the ephemeral internal REST
 * token (see InternalTokenProvider) -- a machine-to-machine credential
 * unrelated to whoever is logged into the Selfie Proxy portal.
 */
@Component
public class BoringProxyClient {

	private final RestClient restClient;
	private final InternalTokenProvider tokenProvider;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public BoringProxyClient(BoringProxyProperties properties, InternalTokenProvider tokenProvider) {
		this.tokenProvider = tokenProvider;
		this.restClient = RestClient.builder()
				.baseUrl(properties.restBaseUrl())
				.defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
					ApiErrorDto error = readError(res);
					throw new BoringProxyException(res.getStatusCode().value(),
							error != null ? error.error() : "BoringProxy request failed");
				})
				.build();
	}

	private ApiErrorDto readError(ClientHttpResponse res) {
		try {
			return objectMapper.readValue(res.getBody(), ApiErrorDto.class);
		} catch (Exception e) {
			return null;
		}
	}

	/** Runs a REST call with the current internal token; on a 401/403 (token rotated by a boringproxy restart), re-reads the token and retries once. */
	private <T> T withToken(Function<String, T> call) {
		try {
			return call.apply(tokenProvider.get());
		} catch (BoringProxyException e) {
			if (e.statusCode() != 401 && e.statusCode() != 403) {
				throw e;
			}
			tokenProvider.invalidate();
			return call.apply(tokenProvider.get());
		}
	}

	public Map<String, AgentStatusDto> listAgents() {
		return withToken(token -> restClient.get()
				.uri("/agents")
				.header("access_token", token)
				.retrieve()
				.body(new ParameterizedTypeReference<Map<String, AgentStatusDto>>() {
				}));
	}

	public void createAgent(String owner, String agentName) {
		withToken((Function<String, Void>) token -> {
			restClient.post()
					.uri("/agents")
					.header("access_token", token)
					.body(new CreateAgentRequestDto(owner, agentName))
					.retrieve()
					.toBodilessEntity();
			return null;
		});
	}

	public void deleteAgent(String owner, String agentName) {
		withToken((Function<String, Void>) token -> {
			restClient.delete()
					.uri("/agents/{owner}/{agentName}", owner, agentName)
					.header("access_token", token)
					.retrieve()
					.toBodilessEntity();
			return null;
		});
	}

	public Map<String, TokenDataDto> listTokens() {
		return withToken(token -> restClient.get()
				.uri("/tokens")
				.header("access_token", token)
				.retrieve()
				.body(new ParameterizedTypeReference<Map<String, TokenDataDto>>() {
				}));
	}

	/** Mints a new secret (an agent-scoped access token) for the given agent. */
	public String createToken(String owner, String agent) {
		CreateTokenResponseDto response = withToken(token -> restClient.post()
				.uri("/tokens")
				.header("access_token", token)
				.body(new CreateTokenRequestDto(owner, agent))
				.retrieve()
				.body(CreateTokenResponseDto.class));
		return response.token();
	}

	/** Re-points an existing secret at a different agent name, keeping the secret string itself unchanged. */
	public void renameTokenAgent(String secret, String newAgentName) {
		withToken((Function<String, Void>) token -> {
			restClient.patch()
					.uri("/tokens/{secret}", secret)
					.header("access_token", token)
					.body(new RenameTokenAgentRequestDto(newAgentName))
					.retrieve()
					.toBodilessEntity();
			return null;
		});
	}

	public void deleteToken(String secret) {
		withToken((Function<String, Void>) token -> {
			restClient.delete()
					.uri("/tokens/{secret}", secret)
					.header("access_token", token)
					.retrieve()
					.toBodilessEntity();
			return null;
		});
	}

	public Map<String, TunnelDto> listTunnels() {
		return withToken(token -> restClient.get()
				.uri("/tunnels")
				.header("access_token", token)
				.retrieve()
				.body(new ParameterizedTypeReference<Map<String, TunnelDto>>() {
				}));
	}

	public TunnelDto createTunnel(CreateTunnelRequestDto request) {
		return withToken(token -> restClient.post()
				.uri("/tunnels")
				.header("access_token", token)
				.body(request)
				.retrieve()
				.body(TunnelDto.class));
	}

	/** Re-points an existing tunnel at a different agent name, without touching its connection details. */
	public void renameTunnelAgent(String domain, String newAgentName) {
		withToken((Function<String, Void>) token -> {
			restClient.patch()
					.uri("/tunnels/{domain}", domain)
					.header("access_token", token)
					.body(new RenameTunnelAgentRequestDto(newAgentName))
					.retrieve()
					.toBodilessEntity();
			return null;
		});
	}

	public TunnelDto getTunnel(String domain) {
		return withToken(token -> restClient.get()
				.uri("/tunnels/{domain}", domain)
				.header("access_token", token)
				.retrieve()
				.body(TunnelDto.class));
	}

	public void deleteTunnel(String domain) {
		withToken((Function<String, Void>) token -> {
			restClient.delete()
					.uri("/tunnels/{domain}", domain)
					.header("access_token", token)
					.retrieve()
					.toBodilessEntity();
			return null;
		});
	}
}
