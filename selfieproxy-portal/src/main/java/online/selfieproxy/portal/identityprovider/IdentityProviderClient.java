package online.selfieproxy.portal.identityprovider;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import online.selfieproxy.portal.identityprovider.dto.ApiErrorDto;
import online.selfieproxy.portal.identityprovider.dto.CreateUserRequestDto;
import online.selfieproxy.portal.identityprovider.dto.UpdateUserRequestDto;
import online.selfieproxy.portal.identityprovider.dto.UserResultDto;
import online.selfieproxy.portal.identityprovider.dto.UserSummaryDto;

import tools.jackson.databind.ObjectMapper;

/**
 * Thin client for identity-provider's internal Users API -- reachable only
 * over the Docker bridge network (identity-provider.internal-base-url points
 * at its second, unpublished Tomcat connector), authenticated with the
 * ephemeral token IdentityProviderTokenProvider reads. Mirrors
 * BoringProxyClient's shape exactly (same RestClient/withToken/ApiErrorDto
 * pattern), just pointed at a different internal service.
 */
@Component
public class IdentityProviderClient {

	private static final String TOKEN_HEADER = "X-Selfieproxy-Internal-Token";

	private final RestClient restClient;
	private final IdentityProviderTokenProvider tokenProvider;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public IdentityProviderClient(IdentityProviderProperties properties, IdentityProviderTokenProvider tokenProvider) {
		this.tokenProvider = tokenProvider;
		this.restClient = RestClient.builder()
				.baseUrl(properties.internalBaseUrl())
				.defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
					ApiErrorDto error = readError(res);
					throw new IdentityProviderException(res.getStatusCode().value(),
							error != null ? error.error() : "Identity provider request failed");
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

	/** Runs a REST call with the current internal token; on a 401 (token rotated by an identity-provider restart), re-reads the token and retries once. */
	private <T> T withToken(Function<String, T> call) {
		try {
			return call.apply(tokenProvider.get());
		} catch (IdentityProviderException e) {
			if (e.statusCode() != 401) {
				throw e;
			}
			tokenProvider.invalidate();
			return call.apply(tokenProvider.get());
		}
	}

	public List<UserSummaryDto> listUsers() {
		return withToken(token -> restClient.get()
				.uri("/internal/users")
				.header(TOKEN_HEADER, token)
				.retrieve()
				.body(new ParameterizedTypeReference<List<UserSummaryDto>>() {
				}));
	}

	public Optional<UserSummaryDto> findUser(String username) {
		try {
			return Optional.ofNullable(withToken(token -> restClient.get()
					.uri("/internal/users/{username}", username)
					.header(TOKEN_HEADER, token)
					.retrieve()
					.body(UserSummaryDto.class)));
		} catch (IdentityProviderException e) {
			if (e.statusCode() == 404) {
				return Optional.empty();
			}
			throw e;
		}
	}

	public UserResultDto createUser(CreateUserRequestDto request) {
		return withToken(token -> restClient.post()
				.uri("/internal/users")
				.header(TOKEN_HEADER, token)
				.body(request)
				.retrieve()
				.body(UserResultDto.class));
	}

	public UserResultDto updateUser(String username, UpdateUserRequestDto request) {
		return withToken(token -> restClient.put()
				.uri("/internal/users/{username}", username)
				.header(TOKEN_HEADER, token)
				.body(request)
				.retrieve()
				.body(UserResultDto.class));
	}

	public void deleteUser(String username) {
		withToken((Function<String, Void>) token -> {
			restClient.delete()
					.uri("/internal/users/{username}", username)
					.header(TOKEN_HEADER, token)
					.retrieve()
					.toBodilessEntity();
			return null;
		});
	}
}
