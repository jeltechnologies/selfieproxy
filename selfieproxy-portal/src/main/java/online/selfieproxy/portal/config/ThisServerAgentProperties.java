package online.selfieproxy.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** THIS_SERVER_AGENT_NAME/THIS_SERVER_SECRET_PATH in .env -- the colocated homelab ThisServerBootstrap creates automatically on first boot. */
@ConfigurationProperties(prefix = "this-server")
public record ThisServerAgentProperties(String agentName, String secretPath) {
}
