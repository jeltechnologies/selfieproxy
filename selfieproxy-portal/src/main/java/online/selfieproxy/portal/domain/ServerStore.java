package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
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

	private static final Logger log = LoggerFactory.getLogger(ServerStore.class);
	private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final Path filePath;
	// New fields are absent -- not merely null -- in servers.json entries written before
	// they existed; without this, Jackson's default record deserialization treats an absent
	// primitive-boolean property as an explicit null and throws instead of defaulting to false,
	// breaking every schema addition since the store's whole point is to evolve over time.
	private final ObjectMapper objectMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.enable(SerializationFeature.INDENT_OUTPUT)
			.build();
	private final Object lock = new Object();

	public ServerStore(@Value("${selfieproxy.servers-path}") String path) {
		this.filePath = Path.of(path);
	}

	/**
	 * Brings a servers.json written by an older image up to the current schema, in place, before
	 * anything reads it. Runs during bean initialisation rather than on ApplicationReadyEvent (the
	 * hook AgentBootstrap/TunnelReconciler use) precisely because it has to happen first: Spring
	 * finishes initialising this bean before injecting it anywhere, so every reader -- including
	 * TunnelReconciler, which rebuilds boringproxy's entire tunnel set from this store on every
	 * startup -- already sees migrated data.
	 *
	 * <p>Unattended by design. A production upgrade is a `docker compose pull` plus `up -d` with no
	 * operator step, so a migration needing one would strand the deployment: until it runs, the
	 * portal can't read servers.json at all (see {@link WebAuthMigration} for why dropping a record
	 * component is fatal here rather than merely lossy).
	 *
	 * <p>Copies the file to a timestamped .bak beside itself before rewriting. That copy is also the
	 * rollback path -- a migrated file is unreadable by the previous image, which would reject
	 * authMethod as an unknown property.
	 */
	@PostConstruct
	void migrate() {
		synchronized (lock) {
			if (!Files.exists(filePath)) {
				return;
			}
			JsonNode root;
			try {
				root = objectMapper.readTree(filePath.toFile());
			} catch (Exception e) {
				throw new IllegalStateException("Failed to read " + filePath + " for migration", e);
			}
			if (!WebAuthMigration.migrateServers(root)) {
				return;
			}
			Path backup = filePath.resolveSibling(
					filePath.getFileName() + ".bak-" + LocalDateTime.now().format(BACKUP_STAMP));
			try {
				Files.copy(filePath, backup, StandardCopyOption.REPLACE_EXISTING);
				objectMapper.writeValue(filePath.toFile(), root);
			} catch (Exception e) {
				throw new IllegalStateException("Failed to migrate " + filePath, e);
			}
			log.info("Migrated {} to the Authentication methods schema (previous contents saved to {})",
					filePath, backup.getFileName());
		}
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
