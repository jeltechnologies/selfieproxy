package online.selfieproxy.remoteconsole.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where selfieproxy-guacd listens -- both this service and guacd run
 * network_mode: host (see docker-compose.yaml), so this is always a plain
 * 127.0.0.1 dial, never a Docker bridge-network hostname.
 */
@ConfigurationProperties(prefix = "guacd")
public record GuacdProperties(String host, int port) {
}
