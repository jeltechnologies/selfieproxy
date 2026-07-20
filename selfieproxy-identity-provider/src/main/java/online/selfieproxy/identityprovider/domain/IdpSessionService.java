package online.selfieproxy.identityprovider.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import online.selfieproxy.identityprovider.config.SsoProperties;

/**
 * This IdP's own login session -- what actually makes sign-on "single":
 * without it, every single-sign-on-protected domain's authorization round trip would
 * always re-show the login form here, even moments after authenticating for
 * a different domain. Now tracks who logged in and whether they're the
 * admin (rather than a bare boolean), since AuthorizeController's
 * silent single-sign-on path needs to know whose session it is, not
 * just that a session exists. In-memory-only, same no-DB/random-token style
 * as AuthorizationService. The session cookie's Max-Age is set to the
 * absolute cap (sessionMaxMinutes) so the browser retains it for the full
 * possible session lifetime; the shorter idle timeout (sessionIdleMinutes)
 * is enforced independently server-side, in this map, and slides on every
 * successful validate().
 */
@Component
public class IdpSessionService {

	private static final String COOKIE_NAME = "selfieproxy_idp_session";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Map<String, Session> sessionsByToken = new ConcurrentHashMap<>();
	private final SsoProperties properties;

	public IdpSessionService(SsoProperties properties) {
		this.properties = properties;
	}

	/** Mints a new session and sets its cookie on the response -- call this once, right after a successful login/password-change. */
	public void startSession(HttpServletResponse response, String username, boolean isAdmin) {
		String token = randomToken();
		Instant now = Instant.now();
		Instant maxExpiry = now.plusSeconds(properties.sessionMaxMinutes() * 60L);
		Instant idleExpiry = capAtMax(now.plusSeconds(properties.sessionIdleMinutes() * 60L), maxExpiry);
		sessionsByToken.put(token, new Session(username, isAdmin, idleExpiry, maxExpiry));

		long maxAgeSeconds = properties.sessionMaxMinutes() * 60L;
		response.addHeader("Set-Cookie", COOKIE_NAME + "=" + token
				+ "; Path=/; Max-Age=" + maxAgeSeconds + "; HttpOnly; Secure; SameSite=Lax");
	}

	/** Present if the browser presents a still-valid session cookie -- slides the idle deadline (capped at the absolute max) on success. */
	public Optional<SessionInfo> validate(HttpServletRequest request) {
		String token = readCookie(request);
		if (token == null) {
			return Optional.empty();
		}
		Session session = sessionsByToken.get(token);
		if (session == null) {
			return Optional.empty();
		}
		Instant now = Instant.now();
		if (now.isAfter(session.idleExpiry()) || now.isAfter(session.maxExpiry())) {
			sessionsByToken.remove(token);
			return Optional.empty();
		}
		Instant slidIdleExpiry = capAtMax(now.plusSeconds(properties.sessionIdleMinutes() * 60L), session.maxExpiry());
		sessionsByToken.put(token, new Session(session.username(), session.isAdmin(), slidIdleExpiry, session.maxExpiry()));
		return Optional.of(new SessionInfo(session.username(), session.isAdmin()));
	}

	/** Ends the session server-side (if any) and clears the cookie -- call on explicit logout. */
	public void invalidate(HttpServletRequest request, HttpServletResponse response) {
		String token = readCookie(request);
		if (token != null) {
			sessionsByToken.remove(token);
		}
		response.addHeader("Set-Cookie", COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax");
	}

	private static String readCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (COOKIE_NAME.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private static Instant capAtMax(Instant candidate, Instant maxExpiry) {
		return candidate.isAfter(maxExpiry) ? maxExpiry : candidate;
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private record Session(String username, boolean isAdmin, Instant idleExpiry, Instant maxExpiry) {
	}

	/** Who a still-valid session belongs to -- returned by validate() for AuthorizeController's silent single-sign-on path. */
	public record SessionInfo(String username, boolean isAdmin) {
	}
}
