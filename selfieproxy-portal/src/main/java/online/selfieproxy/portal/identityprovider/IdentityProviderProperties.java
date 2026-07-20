package online.selfieproxy.portal.identityprovider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** internalBaseUrl points at identity-provider's second, unpublished Tomcat connector -- reachable only over the Docker bridge network, see application.properties. */
@ConfigurationProperties(prefix = "identity-provider")
public record IdentityProviderProperties(String internalBaseUrl) {
}
