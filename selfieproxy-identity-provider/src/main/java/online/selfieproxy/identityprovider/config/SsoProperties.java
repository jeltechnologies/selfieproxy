package online.selfieproxy.identityprovider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * signingKeyPath: where the self-provisioned RSA signing keypair lives on
 * disk (see SigningKeyProvider). issuerUrl: this server's own external base
 * URL, used as the OIDC `issuer` and prefixed onto every endpoint URL in the
 * discovery document. clientRedirectUri: the single hardcoded public
 * client's (client_id=selfieproxy) redirect URI -- no dynamic client
 * registration, any /authorize request with a different redirect_uri is
 * rejected.
 */
@ConfigurationProperties(prefix = "sso")
public record SsoProperties(String signingKeyPath, String issuerUrl, String clientRedirectUri) {
}
