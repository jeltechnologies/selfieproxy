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
 * Read-only view of exposed-apps.json (selfieproxy-portal owns writing it -- see that module's
 * ExposedAppStore/ExposedAppController), keyed by FQDN exactly as that module writes it, filtered
 * to SSH/RDP/VNC-mode Network Services -- every other entry (a Web Application, or a RAW_TCP
 * Network Service) isn't something this service ever bridges. Reread on every find() rather than
 * cached, since the portal can add/edit/remove an app at any time and this service has no way to
 * be notified of that.
 */
@Component
public class RemoteConsoleStore {

	private static final String NETWORK_SERVICE = "NETWORK_SERVICE";

	private final Path filePath;
	// Unknown properties (subdomain, protocol, tlsMode, ssoProtected, domain, Jackson's own
	// derived isX()-style properties, and any future addition) are expected and ignored --
	// this is a deliberately partial mirror of ExposedApp, see RemoteConsole's own javadoc.
	private final ObjectMapper objectMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();

	public RemoteConsoleStore(@Value("${selfieproxy.exposed-apps-path}") String path) {
		this.filePath = Path.of(path);
	}

	public RemoteConsole find(String fqdn) {
		RemoteConsole app = readAll().get(fqdn);
		return isRemoteAccessApp(app) ? app : null;
	}

	private boolean isRemoteAccessApp(RemoteConsole app) {
		return app != null && NETWORK_SERVICE.equals(app.type()) && app.mode() != null
				&& app.mode() != RemoteConsoleProtocol.RAW_TCP;
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
