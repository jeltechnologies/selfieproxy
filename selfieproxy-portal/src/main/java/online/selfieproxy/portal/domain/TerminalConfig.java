package online.selfieproxy.portal.domain;

/**
 * An Application's Terminal (SSH) protocol settings -- present only when Terminal is enabled.
 *
 * @param fqdn            the hidden tunnel's own FQDN (a subdomain of the primary domain, never shown to the
 *                        user -- see HiddenTunnelFqdnAssigner), assigned once and persisted so it stays stable
 * @param port            the homelab-side SSH port, default 22
 * @param exposedPort     the boringproxy-auto-assigned loopback port selfieproxy-remote-console dials -- null
 *                        until the tunnel has actually been created once (see ServerController.syncTunnels)
 * @param username        nullable -- some targets have none
 * @param encryptedSecret AES-GCM ciphertext (NetworkServiceCredentialCipher), null until a credential has been
 *                        entered (eg. via ConsoleConnectController's first-Connect prompt)
 */
public record TerminalConfig(String fqdn, int port, Integer exposedPort, String username, String encryptedSecret) {

	public TerminalConfig withExposedPort(Integer newExposedPort) {
		return new TerminalConfig(fqdn, port, newExposedPort, username, encryptedSecret);
	}

	public TerminalConfig withEncryptedSecret(String newEncryptedSecret) {
		return new TerminalConfig(fqdn, port, exposedPort, username, newEncryptedSecret);
	}

	public TerminalConfig withoutSecret() {
		return encryptedSecret == null ? this : new TerminalConfig(fqdn, port, exposedPort, username, null);
	}
}
