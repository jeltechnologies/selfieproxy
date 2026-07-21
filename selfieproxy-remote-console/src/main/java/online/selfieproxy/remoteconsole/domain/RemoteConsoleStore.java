package online.selfieproxy.remoteconsole.domain;

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
 * Read-only view of remote-consoles.json (selfieproxy-portal owns writing
 * it -- see that module's RemoteConsoleStore/RemoteConsoleController). Reread
 * on every find() rather than cached, since the portal can add/edit/remove a
 * console at any time and this service has no way to be notified of that.
 */
@Component
public class RemoteConsoleStore {

	private final Path filePath;
	private final ObjectMapper objectMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.build();

	public RemoteConsoleStore(@Value("${selfieproxy.remote-consoles-path}") String path) {
		this.filePath = Path.of(path);
	}

	public RemoteConsole find(String id) {
		return readAll().get(id);
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
}
