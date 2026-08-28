package online.selfieproxy.portal.domain;

/**
 * An Application's Web protocol settings -- present only when Web is enabled. The underlying
 * tunnel always lives at the Application's own subdomain+domain (see Server.fqdn()); there's
 * no separate hidden FQDN to track here, unlike Terminal/RemoteDesktop/PortForwarding.
 *
 * @param protocol        how the agent reaches the homelab backend (never how the internet reaches Selfie Proxy -- that's always HTTPS)
 * @param port            the homelab-side port
 * @param authMethod      which of the four gates protects this Server, never null once stored -- see {@link WebAuthMigration} for how pre-existing records acquired one
 * @param basicUsername   Basic username, null unless authMethod is {@link WebAuthMethod#BASIC}
 * @param basicPassword   Basic password, encrypted with NetworkServiceCredentialCipher exactly like a Terminal/RemoteDesktop secret
 * @param tokenHeaderName header carrying the static token, null meaning {@code Authorization} -- see checkTunnelAuth in the reverse proxy's http_proxy.go
 * @param tokenValue      the static token itself, encrypted at rest alongside basicPassword
 */
public record WebConfig(
		Protocol protocol,
		int port,
		WebAuthMethod authMethod,
		String basicUsername,
		String basicPassword,
		String tokenHeaderName,
		String tokenValue) {

	/**
	 * Records written before authMethod existed are migrated on read (see {@link WebAuthMigration}),
	 * so a null here means a record built in memory rather than one loaded from disk -- treat it as
	 * the same default a new Server gets.
	 */
	public WebAuthMethod authMethodOrDefault() {
		return authMethod == null ? WebAuthMethod.SSO : authMethod;
	}

	public boolean isSsoProtected() {
		return authMethodOrDefault() == WebAuthMethod.SSO;
	}

	/** The header the token is presented in -- boringproxy applies the same default, this just keeps the UI honest. */
	public String tokenHeaderNameOrDefault() {
		return tokenHeaderName == null || tokenHeaderName.isBlank() ? "Authorization" : tokenHeaderName;
	}

	/**
	 * Same settings with both credential slots cleared -- see Server.withoutSecrets and BackupService.
	 * A configuration export must never carry a live credential: the ciphertext is bound to this
	 * host's NetworkServiceCredentialCipher key and would be undecryptable anywhere else anyway.
	 */
	public WebConfig withoutSecret() {
		return new WebConfig(protocol, port, authMethod, basicUsername, null, tokenHeaderName, null);
	}
}
