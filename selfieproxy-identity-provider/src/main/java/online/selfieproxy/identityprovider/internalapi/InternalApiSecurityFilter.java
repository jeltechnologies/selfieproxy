package online.selfieproxy.identityprovider.internalapi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import online.selfieproxy.identityprovider.internalapi.dto.ApiErrorDto;

/**
 * Guards every /internal/** request with two independent checks:
 *
 * 1. It must have arrived on internal-api.port, the second Tomcat connector
 *    TomcatInternalConnectorConfig adds -- a request forwarded here on the
 *    main port (8080, the one selfieproxy-reverseproxy's -sso-port targets)
 *    is rejected with a plain 404 before the token is even checked, since
 *    Spring's DispatcherServlet has no notion of "which connector accepted
 *    this" on its own. This is the actual network-isolation boundary: the
 *    internal port is never published in docker-compose.yaml and never
 *    passed as -sso-port, so nothing outside the Docker bridge network can
 *    ever reach it.
 * 2. The X-Selfieproxy-Internal-Token header must match the token
 *    InternalTokenPublisher minted this startup -- defense in depth on top
 *    of (1), not the primary control.
 */
public class InternalApiSecurityFilter implements Filter {

	private static final String TOKEN_HEADER = "X-Selfieproxy-Internal-Token";

	private final InternalTokenPublisher tokenPublisher;
	private final int internalApiPort;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public InternalApiSecurityFilter(InternalTokenPublisher tokenPublisher, int internalApiPort) {
		this.tokenPublisher = tokenPublisher;
		this.internalApiPort = internalApiPort;
	}

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;

		if (request.getLocalPort() != internalApiPort) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		String expected = tokenPublisher.token();
		String presented = request.getHeader(TOKEN_HEADER);
		if (expected == null || presented == null || !MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
			writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid internal API token.");
			return;
		}

		chain.doFilter(request, response);
	}

	private void writeError(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		objectMapper.writeValue(response.getOutputStream(), new ApiErrorDto(message));
	}
}
