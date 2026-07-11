package online.selfieproxy.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AGENT_DEFAULT_NAME in .env -- the agent AgentBootstrap creates automatically on first boot. */
@ConfigurationProperties(prefix = "agent")
public record AgentDefaultsProperties(String defaultName) {
}
