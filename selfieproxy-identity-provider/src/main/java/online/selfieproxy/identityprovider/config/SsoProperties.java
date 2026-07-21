package online.selfieproxy.identityprovider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * signingKeyPath: where the self-provisioned RSA signing keypair lives on
 * disk (see SigningKeyProvider). issuerUrl: this server's own external base
 * URL, used as the OIDC `issuer` and prefixed onto every endpoint URL in the
 * discovery document. clientRedirectUri: the single hardcoded public
 * client's (client_id=selfieproxy) redirect URI -- no dynamic client
 * registration, any /authorize request with a different redirect_uri is
 * rejected. sessionIdleMinutes/sessionMaxMinutes: this IdP's own login
 * session (see IdpSessionService) -- the same SSO_SESSION_IDLE_MINUTES/
 * SSO_SESSION_MAX_MINUTES env vars that size boringproxy's per-app RP
 * cookie also size this session, since both represent the same "how long
 * am I logged in for" concept. primaryDomain: this deployment's own
 * PRIMARY_DOMAIN (read directly from the same env var the rest of the stack
 * bootstraps from, not prefixed with the auth/portal/proxylistener
 * subdomain) -- see LogoutController, which is the only consumer: it uses
 * this to make sure the public, unauthenticated /logged-out?return_to=
 * endpoint can only link back into this deployment's own domain family, not
 * an arbitrary attacker-supplied site.
 */
@ConfigurationProperties(prefix = "sso")
public record SsoProperties(String signingKeyPath, String issuerUrl, String clientRedirectUri,
		long sessionIdleMinutes, long sessionMaxMinutes, String primaryDomain) {
}
