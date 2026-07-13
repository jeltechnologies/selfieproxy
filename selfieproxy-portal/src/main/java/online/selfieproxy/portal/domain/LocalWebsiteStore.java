package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selfie Proxy's own complete record of every LocalWebsite, keyed by domain.
 * Unlike ExposedAppStore, this is the sole source of truth -- there's no
 * boringproxy-side concept to reconcile against, since a Local Website's
 * Tunnel is entirely our own implementation detail (see
 * LocalWebsiteController), not something a user could have created any other
 * way.
 */
@Component
public class LocalWebsiteStore {

	private final Path filePath;
	// ownDomain is absent -- not merely null -- in local-websites.json entries
	// written before it existed; without this, Jackson's default record
	// deserialization treats an absent primitive-boolean property as an
	// explicit null and throws instead of defaulting to false.
	private final ObjectMapper objectMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.build();
	private final Object lock = new Object();

	public LocalWebsiteStore(@Value("${selfieproxy.local-websites-path}") String path) {
		this.filePath = Path.of(path);
	}

	public List<LocalWebsite> list() {
		synchronized (lock) {
			return readAll().values().stream()
					.sorted(Comparator.comparing(LocalWebsite::domain))
					.toList();
		}
	}

	public LocalWebsite find(String domain) {
		synchronized (lock) {
			return readAll().get(domain);
		}
	}

	public void save(LocalWebsite website) {
		synchronized (lock) {
			Map<String, LocalWebsite> all = readAll();
			all.put(website.domain(), website);
			writeAll(all);
		}
	}

	public void delete(String domain) {
		synchronized (lock) {
			Map<String, LocalWebsite> all = readAll();
			if (all.remove(domain) != null) {
				writeAll(all);
			}
		}
	}

	private Map<String, LocalWebsite> readAll() {
		if (!Files.exists(filePath)) {
			return new LinkedHashMap<>();
		}
		JavaType mapType = objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class,
				LocalWebsite.class);
		try {
			return objectMapper.readValue(filePath.toFile(), mapType);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to read " + filePath, e);
		}
	}

	private void writeAll(Map<String, LocalWebsite> all) {
		try {
			Files.createDirectories(filePath.getParent());
			objectMapper.writeValue(filePath.toFile(), all);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to write " + filePath, e);
		}
	}
}
