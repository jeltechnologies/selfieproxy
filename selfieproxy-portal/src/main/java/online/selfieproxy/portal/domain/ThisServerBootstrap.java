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
import online.selfieproxy.portal.config.ThisServerAgentProperties;

/**
 * Creates the "This Server" homelab (an ordinary Agent whose process happens
 * to run colocated in docker-compose.yaml, see the selfieproxy-local-agent
 * service) the first time the portal starts against a boringproxy server
 * that doesn't already know about it, and republishes its current secret to
 * secretPath on every startup -- so the colocated agent container never
 * needs a manual copy-paste from the Agents page, and stays in sync if the
 * secret is ever regenerated there. Mirrors AgentBootstrap's pattern for
 * DEFAULT_HOMELAB.
 */
@Component
public class ThisServerBootstrap {

	private static final Logger log = LoggerFactory.getLogger(ThisServerBootstrap.class);
	private static final String OWNER = "admin";

	private final BoringProxyClient boringProxyClient;
	private final ThisServerAgentProperties properties;

	public ThisServerBootstrap(BoringProxyClient boringProxyClient, ThisServerAgentProperties properties) {
		this.boringProxyClient = boringProxyClient;
		this.properties = properties;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void ensureThisServerAgent() {
		String name = properties.agentName();

		Map<String, AgentStatusDto> agents = boringProxyClient.listAgents();
		String secret = agents.containsKey(name) ? findSecret(name) : null;

		if (secret == null) {
			if (!agents.containsKey(name)) {
				boringProxyClient.createAgent(OWNER, name);
				log.info("Created 'This Server' homelab '{}'.", name);
			}
			secret = boringProxyClient.createToken(OWNER, name);
		}

		writeSecret(secret);
	}

	private String findSecret(String name) {
		return boringProxyClient.listTokens().entrySet().stream()
				.filter(e -> name.equals(e.getValue().agent()))
				.map(Map.Entry::getKey)
				.findFirst()
				.orElse(null);
	}

	/** A missing/blank secret (eg. a transient boringproxy hiccup) is logged, not fatal -- the colocated agent just waits and selfieproxy-local-agent's own retry loop tries again on the next portal restart, rather than the whole portal refusing to start. */
	private void writeSecret(String secret) {
		if (secret == null || secret.isBlank()) {
			log.warn("Could not resolve a secret for 'This Server' homelab '{}'; selfieproxy-local-agent will remain disconnected until the next successful portal startup.",
					properties.agentName());
			return;
		}
		try {
			Path path = Path.of(properties.secretPath());
			Files.createDirectories(path.getParent());
			Files.writeString(path, secret);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to write This Server agent secret to " + properties.secretPath(), e);
		}
	}
}
