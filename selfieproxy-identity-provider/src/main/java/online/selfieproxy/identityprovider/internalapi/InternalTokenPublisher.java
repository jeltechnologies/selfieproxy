package online.selfieproxy.identityprovider.internalapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Mints a fresh random token every startup and writes it to internal-api.token-path
 * (inside the /data volume this app already shares with selfieproxy-portal), mirroring
 * boringproxy's own ephemeral-REST-token pattern (see selfieproxy-portal's
 * InternalTokenProvider) but in the opposite role -- this app is the token
 * publisher/server, the portal is the reader/client. Never persisted anywhere
 * else, changes on every restart. Kept cached for InternalApiSecurityFilter's
 * constant-time comparison against every /internal/** request.
 */
@Component
public class InternalTokenPublisher {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final Path tokenPath;
	private volatile String token;

	public InternalTokenPublisher(@Value("${internal-api.token-path}") String tokenPath) {
		this.tokenPath = Path.of(tokenPath);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void publish() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		try {
			Files.createDirectories(tokenPath.getParent());
			Files.writeString(tokenPath, token);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to write internal API token to " + tokenPath, e);
		}
	}

	public String token() {
		return token;
	}
}
