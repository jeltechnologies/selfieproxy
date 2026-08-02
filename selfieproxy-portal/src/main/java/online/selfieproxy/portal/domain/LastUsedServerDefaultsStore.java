package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Remembers the domain and homelab most recently used to add an Exposed Application, so the Add
 * Application page's Domain/Homelab dropdowns default to them instead of always the primary
 * domain/first homelab -- same self-contained single-value JSON store shape as ThemeStore. A
 * missing or corrupt file is never fatal (load() returns null and ServerController falls back
 * to those defaults), since this is a convenience default, not load-bearing state.
 */
@Component
public class LastUsedServerDefaultsStore {

	private static final Logger log = LoggerFactory.getLogger(LastUsedServerDefaultsStore.class);

	private final Path filePath;
	private final ObjectMapper objectMapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
	private final Object lock = new Object();

	public LastUsedServerDefaultsStore(@Value("${selfieproxy.last-used-server-defaults-path}") String path) {
		this.filePath = Path.of(path);
	}

	/** Null if nothing has been recorded yet (or the record is unreadable) -- otherwise either field may still individually be null. */
	public LastUsedServerDefaults load() {
		synchronized (lock) {
			if (!Files.exists(filePath)) {
				return null;
			}
			try {
				return objectMapper.readValue(filePath.toFile(), LastUsedServerDefaults.class);
			} catch (Exception e) {
				log.warn("Failed to read {}, falling back to no remembered defaults", filePath, e);
				return null;
			}
		}
	}

	public void save(LastUsedServerDefaults defaults) {
		synchronized (lock) {
			try {
				Files.createDirectories(filePath.getParent());
				objectMapper.writeValue(filePath.toFile(), defaults);
			} catch (IOException e) {
				throw new IllegalStateException("Failed to write " + filePath, e);
			}
		}
	}
}
