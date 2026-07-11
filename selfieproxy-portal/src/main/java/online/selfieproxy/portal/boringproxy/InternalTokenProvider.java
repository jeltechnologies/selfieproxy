package online.selfieproxy.portal.boringproxy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the ephemeral, in-memory-only REST token boringproxy writes at every
 * startup (see boringproxy's -runtime-dir flag). The token is never persisted
 * by boringproxy and changes on every restart, so it's read lazily and cached
 * -- {@link #invalidate()} forces a re-read after a 401/403 from boringproxy.
 */
@Component
public class InternalTokenProvider {

	private final Path tokenPath;
	private volatile String cached;

	public InternalTokenProvider(@Value("${boringproxy.internal-token-path}") String tokenPath) {
		this.tokenPath = Path.of(tokenPath);
	}

	public String get() {
		String value = cached;
		if (value != null) {
			return value;
		}
		return read();
	}

	public void invalidate() {
		cached = null;
	}

	private synchronized String read() {
		if (cached != null) {
			return cached;
		}
		try {
			cached = Files.readString(tokenPath).strip();
		} catch (IOException e) {
			throw new IllegalStateException(
					"Internal REST token not yet available at " + tokenPath + " (has boringproxy started?)", e);
		}
		return cached;
	}
}
