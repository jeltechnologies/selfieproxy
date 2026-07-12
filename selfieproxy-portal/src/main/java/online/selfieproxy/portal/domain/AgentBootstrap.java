package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.AgentStatusDto;
import online.selfieproxy.portal.config.AgentDefaultsProperties;

/**
 * Creates the default agent (DEFAULT_HOMELAB) the first time the portal ever
 * starts against a boringproxy server, then never again -- bootstrapMarkerPath
 * is written once bootstrap has run, so a user who later deletes the default
 * homelab does not get it silently recreated on the next restart. Runs on
 * {@link ApplicationReadyEvent} rather than a plain CommandLineRunner/@PostConstruct
 * because BoringProxyClient depends on boringproxy's runtime token file already
 * existing on disk.
 */
@Component
public class AgentBootstrap {

	private static final Logger log = LoggerFactory.getLogger(AgentBootstrap.class);
	private static final String OWNER = "admin";

	private final BoringProxyClient boringProxyClient;
	private final AgentDefaultsProperties properties;

	public AgentBootstrap(BoringProxyClient boringProxyClient, AgentDefaultsProperties properties) {
		this.boringProxyClient = boringProxyClient;
		this.properties = properties;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void createDefaultAgentIfMissing() {
		Path markerPath = Path.of(properties.bootstrapMarkerPath());
		if (Files.exists(markerPath)) {
			log.info("Default homelab bootstrap already ran previously, skipping.");
			return;
		}

		String name = properties.defaultName();
		Map<String, AgentStatusDto> agents = boringProxyClient.listAgents();
		if (agents.containsKey(name)) {
			log.info("Default agent '{}' already exists, skipping bootstrap.", name);
		} else {
			boringProxyClient.createAgent(OWNER, name);
			boringProxyClient.createToken(OWNER, name);
			log.info("Created default agent '{}'. View its secret on the Agents page.", name);
		}

		writeMarker(markerPath);
	}

	private void writeMarker(Path markerPath) {
		try {
			Files.createDirectories(markerPath.getParent());
			Files.writeString(markerPath, "");
		} catch (IOException e) {
			throw new IllegalStateException("Failed to write default homelab bootstrap marker to " + markerPath, e);
		}
	}
}
