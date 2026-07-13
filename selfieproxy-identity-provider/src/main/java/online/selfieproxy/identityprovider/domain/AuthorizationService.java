package online.selfieproxy.identityprovider.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-memory-only (no DB) tracking of pending and completed OIDC
 * authorization requests, keyed by a random authz_id. Each authz_id is its
 * own short-lived flow, marked authenticated once (by LoginController or
 * ChangePasswordController) and then consumed exactly once by issueCode --
 * it does not by itself remember the user across requests. Cross-request
 * "am I already logged in" recognition is IdpSessionService's job (this
 * IdP's own login session cookie), not this class's.
 */
@Component
public class AuthorizationService {

	private static final long CODE_TTL_SECONDS = 60;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Map<String, PendingAuthorization> pendingByAuthzId = new ConcurrentHashMap<>();
	private final Map<String, IssuedCode> codesByCode = new ConcurrentHashMap<>();

	/** Starts a new pending authorization request; caller must have already validated client_id/redirect_uri. */
	public String start(String redirectUri, String codeChallenge, String codeChallengeMethod, String state) {
		String authzId = randomToken();
		pendingByAuthzId.put(authzId, new PendingAuthorization(redirectUri, codeChallenge, codeChallengeMethod, state, false));
		return authzId;
	}

	public PendingAuthorization get(String authzId) {
		return pendingByAuthzId.get(authzId);
	}

	public void markAuthenticated(String authzId) {
		pendingByAuthzId.computeIfPresent(authzId, (id, pending) -> pending.withAuthenticated(true));
	}

	/** One-time: removes the pending authorization and mints a short-lived code bound to the same redirect_uri/code_challenge, for /token to verify later. Returns null if authzId is unknown or not yet authenticated. */
	public String issueCode(String authzId) {
		PendingAuthorization pending = pendingByAuthzId.get(authzId);
		if (pending == null || !pending.authenticated()) {
			return null;
		}
		pendingByAuthzId.remove(authzId);

		String code = randomToken();
		Instant expiry = Instant.now().plusSeconds(CODE_TTL_SECONDS);
		codesByCode.put(code, new IssuedCode(pending.redirectUri(), pending.codeChallenge(), pending.codeChallengeMethod(), expiry));
		return code;
	}

	public String stateFor(String authzId) {
		PendingAuthorization pending = pendingByAuthzId.get(authzId);
		return pending == null ? null : pending.state();
	}

	/** One-time use -- removed on consume regardless of validity, plus a lazy sweep of any other stale entries on every access (no background thread needed). */
	public IssuedCode consumeCode(String code) {
		sweepExpired();
		IssuedCode issued = codesByCode.remove(code);
		if (issued == null || issued.isExpired()) {
			return null;
		}
		return issued;
	}

	private void sweepExpired() {
		codesByCode.values().removeIf(IssuedCode::isExpired);
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public record PendingAuthorization(String redirectUri, String codeChallenge, String codeChallengeMethod, String state,
			boolean authenticated) {
		PendingAuthorization withAuthenticated(boolean value) {
			return new PendingAuthorization(redirectUri, codeChallenge, codeChallengeMethod, state, value);
		}
	}

	public record IssuedCode(String redirectUri, String codeChallenge, String codeChallengeMethod, Instant expiry) {
		boolean isExpired() {
			return Instant.now().isAfter(expiry);
		}
	}
}
