package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
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
 * Selfie Proxy's own complete, authoritative record of every Application, keyed by its full FQDN
 * (subdomain + domain -- see Server.fqdn()). Unlike the single-protocol model this replaces,
 * this key is no longer necessarily identical to any one boringproxy Tunnel's own domain -- a
 * Server's hidden Terminal/RemoteDesktop/PortForwarding tunnels each live at their own,
 * separately-generated FQDN (see HiddenTunnelFqdnAssigner), so a hidden tunnel's FQDN can't be
 * reverse-parsed back to "which Server, which protocol" the way a single-tunnel-per-server model
 * could. This store is therefore the sole source of truth for the Server list -- boringproxy
 * tunnels are a derived side-effect kept in sync on save (see ServerController/BackupService),
 * never reconstructed from live tunnel data on read.
 */
@Component
public class ServerStore {

	private final Path filePath;
	// New fields are absent -- not merely null -- in servers.json entries written before
	// they existed; without this, Jackson's default record deserialization treats an absent
	// primitive-boolean property as an explicit null and throws instead of defaulting to false,
	// breaking every schema addition since the store's whole point is to evolve over time.
	private final ObjectMapper objectMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.build();
	private final Object lock = new Object();

	public ServerStore(@Value("${selfieproxy.servers-path}") String path) {
		this.filePath = Path.of(path);
	}

	public void save(Server server) {
		synchronized (lock) {
			Map<String, Server> all = readAll();
			all.put(server.fqdn(), server);
			writeAll(all);
		}
	}

	public void delete(String fqdn) {
		synchronized (lock) {
			Map<String, Server> all = readAll();
			if (all.remove(fqdn) != null) {
				writeAll(all);
			}
		}
	}

	/** The stored record for fqdn, or null if none exists. */
	public Server find(String fqdn) {
		synchronized (lock) {
			return readAll().get(fqdn);
		}
	}

	/** Every stored Application -- the Applications list is built directly from this, not from live boringproxy tunnels. */
	public Collection<Server> values() {
		synchronized (lock) {
			return List.copyOf(readAll().values());
		}
	}

	private Map<String, Server> readAll() {
		if (!Files.exists(filePath)) {
			return new LinkedHashMap<>();
		}
		JavaType mapType = objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class,
				Server.class);
		try {
			return objectMapper.readValue(filePath.toFile(), mapType);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to read " + filePath, e);
		}
	}

	private void writeAll(Map<String, Server> all) {
		try {
			Files.createDirectories(filePath.getParent());
			objectMapper.writeValue(filePath.toFile(), all);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to write " + filePath, e);
		}
	}
}
