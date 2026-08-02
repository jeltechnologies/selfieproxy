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
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only view of servers.json (selfieproxy-portal owns writing it -- see that module's
 * ServerStore/ServerController). Since one Application can now expose up to 4 protocols at
 * once, the file's top-level map is keyed by each Application's own public FQDN, not by the hidden
 * Terminal/RemoteDesktop tunnel FQDN this service is actually asked to dial (that hidden FQDN lives
 * nested inside the Application's own terminal/remoteDesktop fields instead -- see
 * HiddenTunnelFqdnAssigner on the portal side). find() therefore builds a small in-memory index
 * from hidden FQDN to its RemoteConsole on every call, rather than a direct map lookup. Reread on
 * every find() rather than cached, since the portal can add/edit/remove an server at any time and this
 * service has no way to be notified of that.
 */
@Component
public class RemoteConsoleStore {

	private final Path filePath;
	// Unknown properties (subdomain, domain, host, web, portForwarding, and any future addition)
	// are expected and ignored -- this is a deliberately partial mirror of Server, see
	// RemoteConsole's own javadoc.
	private final ObjectMapper objectMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(SerializationFeature.INDENT_OUTPUT)
			.build();

	public RemoteConsoleStore(@Value("${selfieproxy.servers-path}") String path) {
		this.filePath = Path.of(path);
	}

	/** hiddenFqdn is a Terminal or Remote Desktop tunnel's own FQDN (never an Application's public-facing one). */
	public RemoteConsole find(String hiddenFqdn) {
		for (RemoteConsoleServer server : readAll().values()) {
			RemoteConsoleTerminal terminal = server.terminal();
			if (terminal != null && hiddenFqdn.equalsIgnoreCase(terminal.fqdn())) {
				return new RemoteConsole(RemoteConsoleProtocol.SSH, terminal.exposedPort(), terminal.username(),
						terminal.encryptedSecret(), false);
			}
			RemoteConsoleRemoteDesktop remoteDesktop = server.remoteDesktop();
			if (remoteDesktop != null && hiddenFqdn.equalsIgnoreCase(remoteDesktop.fqdn())) {
				return new RemoteConsole(remoteDesktop.protocol(), remoteDesktop.exposedPort(),
						remoteDesktop.username(), remoteDesktop.encryptedSecret(), remoteDesktop.ignoreCertificate());
			}
		}
		return null;
	}

	private Map<String, RemoteConsoleServer> readAll() {
		if (!Files.exists(filePath)) {
			return new LinkedHashMap<>();
		}
		JavaType mapType = objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class,
				RemoteConsoleServer.class);
		try {
			return objectMapper.readValue(filePath.toFile(), mapType);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to read " + filePath, e);
		}
	}
}
