package online.selfieproxy.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * issuerUrl mirrors OIDC_ISSUER_URL, the same env var boringproxy reads for
 * its -oidc-issuer flag -- blank by default (bundled
 * selfieproxy-identity-provider), non-blank when an operator has swapped in
 * an external IdP. See GlobalModelAttributes.
 */
@ConfigurationProperties(prefix = "oidc")
public record OidcProperties(String issuerUrl) {

	public boolean isExternal() {
		return issuerUrl != null && !issuerUrl.isBlank();
	}
}
