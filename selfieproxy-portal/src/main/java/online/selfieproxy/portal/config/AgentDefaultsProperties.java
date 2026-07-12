package online.selfieproxy.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DEFAULT_HOMELAB in .env -- the agent AgentBootstrap creates automatically on
 * first boot. bootstrapMarkerPath tracks whether that one-time bootstrap
 * already ran, so a default homelab the user later deletes is not recreated
 * on a subsequent restart.
 */
@ConfigurationProperties(prefix = "agent")
public record AgentDefaultsProperties(String defaultName, String bootstrapMarkerPath) {
}
