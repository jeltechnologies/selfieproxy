package online.selfieproxy.identityprovider.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import online.selfieproxy.identityprovider.config.AdminAuthProperties;
import online.selfieproxy.identityprovider.config.SsoProperties;
import online.selfieproxy.identityprovider.domain.AuthorizationService;
import online.selfieproxy.identityprovider.domain.AuthorizationService.IssuedCode;
import online.selfieproxy.identityprovider.domain.SigningKeyProvider;

/** Standard authorization-code + PKCE exchange (S256 only -- this bundled IdP doesn't support the "plain" code_challenge_method) for the single hardcoded public client. */
@RestController
public class TokenController {

	private static final String CLIENT_ID = "selfieproxy";
	private static final String SUBJECT = "admin";
	private static final long ID_TOKEN_TTL_SECONDS = 3600;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final AuthorizationService authorizationService;
	private final SigningKeyProvider signingKeyProvider;
	private final SsoProperties ssoProperties;
	private final AdminAuthProperties adminAuthProperties;

	public TokenController(AuthorizationService authorizationService, SigningKeyProvider signingKeyProvider,
			SsoProperties ssoProperties, AdminAuthProperties adminAuthProperties) {
		this.authorizationService = authorizationService;
		this.signingKeyProvider = signingKeyProvider;
		this.ssoProperties = ssoProperties;
		this.adminAuthProperties = adminAuthProperties;
	}

	@PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public ResponseEntity<Map<String, Object>> token(
			@RequestParam("grant_type") String grantType,
			@RequestParam("code") String code,
			@RequestParam("redirect_uri") String redirectUri,
			@RequestParam("code_verifier") String codeVerifier,
			@RequestParam(value = "client_id", required = false) String clientId) {

		if (!"authorization_code".equals(grantType)) {
			return badRequest("unsupported_grant_type");
		}
		if (clientId != null && !CLIENT_ID.equals(clientId)) {
			return badRequest("invalid_client");
		}

		IssuedCode issued = authorizationService.consumeCode(code);
		if (issued == null) {
			return badRequest("invalid_grant");
		}
		if (!issued.redirectUri().equals(redirectUri)) {
			return badRequest("invalid_grant");
		}
		if (!"S256".equals(issued.codeChallengeMethod())) {
			return badRequest("unsupported_code_challenge_method");
		}
		if (!verifyPkce(codeVerifier, issued.codeChallenge())) {
			return badRequest("invalid_grant");
		}

		try {
			Map<String, Object> body = Map.of(
					"access_token", randomToken(),
					"token_type", "Bearer",
					"expires_in", ID_TOKEN_TTL_SECONDS,
					"id_token", mintIdToken());
			return ResponseEntity.ok(body);
		} catch (JOSEException e) {
			throw new IllegalStateException("Failed to sign ID token", e);
		}
	}

	private boolean verifyPkce(String codeVerifier, String storedChallenge) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
			String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
			return computed.equals(storedChallenge);
		} catch (NoSuchAlgorithmException e) {
			return false;
		}
	}

	private String mintIdToken() throws JOSEException {
		RSAKey rsaKey = signingKeyProvider.getRsaKey();
		Instant now = Instant.now();
		String email = adminAuthProperties.username();

		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(ssoProperties.issuerUrl())
				.subject(SUBJECT)
				.audience(CLIENT_ID)
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plusSeconds(ID_TOKEN_TTL_SECONDS)))
				.claim("email", email)
				.claim("name", email)
				.build();

		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build();
		SignedJWT signedJWT = new SignedJWT(header, claims);
		signedJWT.sign(new RSASSASigner(rsaKey));
		return signedJWT.serialize();
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private ResponseEntity<Map<String, Object>> badRequest(String error) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", error));
	}
}
