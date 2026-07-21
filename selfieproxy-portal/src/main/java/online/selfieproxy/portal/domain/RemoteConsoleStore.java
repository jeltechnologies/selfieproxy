package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selfie Proxy's own complete record of every RemoteConsole, keyed by id.
 * Unlike ExposedAppStore, there is no BoringProxy-authored counterpart to
 * reconcile against -- these records are wholly owned here, and the separate
 * selfieproxy-remote-console service only ever reads this same file (shared
 * /data volume) at connect time, never writes it.
 */
@Component
public class RemoteConsoleStore {

	private final Path filePath;
	// Same rationale as ExposedAppStore: an absent boolean field in a record
	// written before it existed (eg. a future schema addition) must default to
	// false rather than fail deserialization.
	private final ObjectMapper objectMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.build();
	private final Object lock = new Object();

	public RemoteConsoleStore(@Value("${selfieproxy.remote-consoles-path}") String path) {
		this.filePath = Path.of(path);
	}

	public void save(RemoteConsole console) {
		synchronized (lock) {
			Map<String, RemoteConsole> all = readAll();
			all.put(console.id(), console);
			writeAll(all);
		}
	}

	public void delete(String id) {
		synchronized (lock) {
			Map<String, RemoteConsole> all = readAll();
			if (all.remove(id) != null) {
				writeAll(all);
			}
		}
	}

	public RemoteConsole find(String id) {
		synchronized (lock) {
			return readAll().get(id);
		}
	}

	public java.util.List<RemoteConsole> findAll() {
		synchronized (lock) {
			return new java.util.ArrayList<>(readAll().values());
		}
	}

	private Map<String, RemoteConsole> readAll() {
		if (!Files.exists(filePath)) {
			return new LinkedHashMap<>();
		}
		JavaType mapType = objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class,
				RemoteConsole.class);
		try {
			return objectMapper.readValue(filePath.toFile(), mapType);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to read " + filePath, e);
		}
	}

	private void writeAll(Map<String, RemoteConsole> all) {
		try {
			Files.createDirectories(filePath.getParent());
			objectMapper.writeValue(filePath.toFile(), all);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to write " + filePath, e);
		}
	}
}
