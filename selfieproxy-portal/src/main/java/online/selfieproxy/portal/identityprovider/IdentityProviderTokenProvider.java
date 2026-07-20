package online.selfieproxy.portal.identityprovider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the ephemeral internal-API token identity-provider's InternalTokenPublisher
 * writes at every startup -- same shared-/data-volume, read-lazily-and-cache
 * pattern as boringproxy's own InternalTokenProvider, just pointed at
 * identity-provider's token file instead. {@link #invalidate()} forces a
 * re-read after a 401 from identity-provider (its token rotates on restart).
 */
@Component
public class IdentityProviderTokenProvider {

	private final Path tokenPath;
	private volatile String cached;

	public IdentityProviderTokenProvider(@Value("${identity-provider.internal-token-path}") String tokenPath) {
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
					"Internal API token not yet available at " + tokenPath + " (has identity-provider started?)", e);
		}
		return cached;
	}
}
