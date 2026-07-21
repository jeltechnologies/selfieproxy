package online.selfieproxy.identityprovider.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-memory-only (no DB) exponential-backoff throttle for LoginController's
 * /login, keyed by the exact submitted username string (same equality
 * semantics LoginController/UserStore already use, no case-folding). The
 * first THRESHOLD failures for a username are free (typos happen); each
 * failure after that doubles the required wait before the next attempt is
 * even checked, starting at BASE_DELAY and capped at MAX_DELAY -- the same
 * shape AWS Cognito and OWASP's cheat sheet both describe. A hard lockout
 * (reject forever until an admin/self-service unlock) is deliberately not
 * used: this deployment has exactly one admin account, so a hard lockout
 * keyed on username would let anyone who merely knows/guesses the admin
 * username lock the real admin out indefinitely just by submitting wrong
 * passwords -- a self-inflicted DoS. Backoff only slows guessing down, it
 * never fully blocks the legitimate owner. A successful login clears the
 * counter. Same no-background-thread lazy-sweep style as
 * AuthorizationService.
 */
@Component
public class LoginAttemptTracker {

	private static final int FREE_ATTEMPTS = 5;
	private static final long BASE_DELAY_SECONDS = 1;
	private static final long MAX_DELAY_SECONDS = 15 * 60;
	private static final int MAX_BACKOFF_EXPONENT = 20; // 1s << 20 already dwarfs MAX_DELAY_SECONDS
	private static final Duration STALE_AFTER = Duration.ofHours(1); // well past MAX_DELAY_SECONDS

	private final Map<String, Attempt> attemptsByUsername = new ConcurrentHashMap<>();

	/** Seconds until this username may attempt login again, or 0 if it isn't currently backed off. */
	public long secondsUntilNextAttempt(String username) {
		Attempt attempt = attemptsByUsername.get(username);
		if (attempt == null) {
			return 0;
		}
		long remaining = Instant.now().until(attempt.lockedUntil(), java.time.temporal.ChronoUnit.SECONDS);
		return Math.max(0, remaining);
	}

	/** Records a failed attempt, extending the backoff for the next one once FREE_ATTEMPTS is exceeded. */
	public void recordFailure(String username) {
		sweepStale();
		Instant now = Instant.now();
		attemptsByUsername.compute(username, (key, existing) -> {
			int count = (existing == null ? 0 : existing.count()) + 1;
			Instant lockedUntil = now;
			if (count > FREE_ATTEMPTS) {
				int exponent = Math.min(MAX_BACKOFF_EXPONENT, count - FREE_ATTEMPTS - 1);
				long delaySeconds = Math.min(MAX_DELAY_SECONDS, BASE_DELAY_SECONDS << exponent);
				lockedUntil = now.plusSeconds(delaySeconds);
			}
			return new Attempt(count, lockedUntil, now);
		});
	}

	/** Clears backoff state for this username after a successful login. */
	public void recordSuccess(String username) {
		attemptsByUsername.remove(username);
	}

	private void sweepStale() {
		Instant cutoff = Instant.now().minus(STALE_AFTER);
		attemptsByUsername.values().removeIf(attempt -> attempt.lastFailureAt().isBefore(cutoff));
	}

	private record Attempt(int count, Instant lockedUntil, Instant lastFailureAt) {
	}
}
