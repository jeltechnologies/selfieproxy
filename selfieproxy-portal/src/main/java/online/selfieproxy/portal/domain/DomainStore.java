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

import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selfie Proxy's own record of every registered secondary domain, keyed by
 * name. The primary domain is deliberately never stored here -- it always
 * comes from BoringProxyProperties.primaryDomain()/the PRIMARY_DOMAIN env var
 * and can never be renamed or removed (see DomainService).
 */
@Component
public class DomainStore {

	private final Path filePath;
	private final ObjectMapper objectMapper = JsonMapper.builder().build();
	private final Object lock = new Object();

	public DomainStore(@Value("${selfieproxy.domains-path}") String path) {
		this.filePath = Path.of(path);
	}

	public List<SecondaryDomain> list() {
		synchronized (lock) {
			return readAll().values().stream()
					.sorted(Comparator.comparing(SecondaryDomain::name))
					.toList();
		}
	}

	public SecondaryDomain find(String name) {
		synchronized (lock) {
			return readAll().get(name);
		}
	}

	public void save(SecondaryDomain domain) {
		synchronized (lock) {
			Map<String, SecondaryDomain> all = readAll();
			all.put(domain.name(), domain);
			writeAll(all);
		}
	}

	public void delete(String name) {
		synchronized (lock) {
			Map<String, SecondaryDomain> all = readAll();
			if (all.remove(name) != null) {
				writeAll(all);
			}
		}
	}

	private Map<String, SecondaryDomain> readAll() {
		if (!Files.exists(filePath)) {
			return new LinkedHashMap<>();
		}
		JavaType mapType = objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class,
				SecondaryDomain.class);
		try {
			return objectMapper.readValue(filePath.toFile(), mapType);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to read " + filePath, e);
		}
	}

	private void writeAll(Map<String, SecondaryDomain> all) {
		try {
			Files.createDirectories(filePath.getParent());
			objectMapper.writeValue(filePath.toFile(), all);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to write " + filePath, e);
		}
	}
}
