package online.selfieproxy.portal.domain;

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
 * Creates the default agent (AGENT_DEFAULT_NAME) the first time the portal
 * starts against a boringproxy server that doesn't already know about it.
 * Runs on {@link ApplicationReadyEvent} rather than a plain
 * CommandLineRunner/@PostConstruct because BoringProxyClient depends on
 * boringproxy's runtime token file already existing on disk.
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
		String name = properties.defaultName();

		Map<String, AgentStatusDto> agents = boringProxyClient.listAgents();
		if (agents.containsKey(name)) {
			log.info("Default agent '{}' already exists, skipping bootstrap.", name);
			return;
		}

		boringProxyClient.createAgent(OWNER, name);
		boringProxyClient.createToken(OWNER, name);

		log.info("Created default agent '{}'. View its secret on the Agents page.", name);
	}
}
