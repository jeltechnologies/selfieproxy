package online.selfieproxy.identityprovider.web;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletResponse;

import online.selfieproxy.identityprovider.config.SsoProperties;
import online.selfieproxy.identityprovider.domain.AuthorizationService;
import online.selfieproxy.identityprovider.domain.AuthorizationService.PendingAuthorization;

/**
 * Starts/continues the OIDC authorization-code + PKCE flow for the single
 * hardcoded public client (client_id=selfieproxy). No dynamic client
 * registration: client_id and redirect_uri are validated against
 * sso.client-redirect-uri on every fresh request, and a mismatch is a flat
 * 400 -- never a redirect, since the redirect_uri itself isn't trusted yet.
 */
@Controller
public class AuthorizeController {

	private static final String CLIENT_ID = "selfieproxy";

	private final SsoProperties properties;
	private final AuthorizationService authorizationService;

	public AuthorizeController(SsoProperties properties, AuthorizationService authorizationService) {
		this.properties = properties;
		this.authorizationService = authorizationService;
	}

	@GetMapping("/authorize")
	public String authorize(
			@RequestParam(value = "response_type", required = false) String responseType,
			@RequestParam(value = "client_id", required = false) String clientId,
			@RequestParam(value = "redirect_uri", required = false) String redirectUri,
			@RequestParam(value = "state", required = false) String state,
			@RequestParam(value = "code_challenge", required = false) String codeChallenge,
			@RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
			@RequestParam(value = "authz_id", required = false) String authzId,
			HttpServletResponse response) throws IOException {

		if (authzId != null) {
			return continuePending(authzId, response);
		}
		return startNew(responseType, clientId, redirectUri, state, codeChallenge, codeChallengeMethod, response);
	}

	private String continuePending(String authzId, HttpServletResponse response) throws IOException {
		PendingAuthorization pending = authorizationService.get(authzId);
		if (pending == null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown or expired authorization request.");
			return null;
		}
		if (!pending.authenticated()) {
			return "redirect:/login?authz_id=" + authzId;
		}

		String code = authorizationService.issueCode(authzId);
		if (code == null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown or expired authorization request.");
			return null;
		}

		String state = pending.state() == null ? "" : pending.state();
		String redirect = pending.redirectUri()
				+ "?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
				+ "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
		return "redirect:" + redirect;
	}

	private String startNew(String responseType, String clientId, String redirectUri, String state,
			String codeChallenge, String codeChallengeMethod, HttpServletResponse response) throws IOException {
		if (!CLIENT_ID.equals(clientId) || redirectUri == null || !redirectUri.equals(properties.clientRedirectUri())) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown client_id or redirect_uri.");
			return null;
		}
		if (!"code".equals(responseType)) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported response_type.");
			return null;
		}
		if (codeChallenge == null || codeChallenge.isBlank() || !"S256".equals(codeChallengeMethod)) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "code_challenge_method must be S256.");
			return null;
		}

		String authzId = authorizationService.start(redirectUri, codeChallenge, codeChallengeMethod, state);
		return "redirect:/login?authz_id=" + authzId;
	}
}
