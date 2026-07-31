package online.selfieproxy.portal.domain;

/**
 * An Application's Remote Desktop (RDP/VNC) protocol settings -- present only when Remote Desktop
 * is enabled.
 *
 * @param fqdn              the hidden tunnel's own FQDN (a subdomain of the primary domain, never shown to
 *                          the user -- see HiddenTunnelFqdnAssigner), assigned once and persisted
 * @param protocol          RDP (default) or VNC
 * @param port              the homelab-side port, default 3389 (RDP) / 5900 (VNC)
 * @param exposedPort       the boringproxy-auto-assigned loopback port selfieproxy-remote-console dials --
 *                          null until the tunnel has actually been created once
 * @param username          nullable -- VNC often has none
 * @param encryptedSecret   AES-GCM ciphertext (NetworkServiceCredentialCipher), null until entered
 * @param ignoreCertificate accept a self-signed certificate on the target
 */
public record RemoteDesktopConfig(String fqdn, RemoteDesktopProtocol protocol, int port, Integer exposedPort,
		String username, String encryptedSecret, boolean ignoreCertificate) {

	public RemoteDesktopConfig withExposedPort(Integer newExposedPort) {
		return new RemoteDesktopConfig(fqdn, protocol, port, newExposedPort, username, encryptedSecret, ignoreCertificate);
	}

	public RemoteDesktopConfig withEncryptedSecret(String newEncryptedSecret) {
		return new RemoteDesktopConfig(fqdn, protocol, port, exposedPort, username, newEncryptedSecret, ignoreCertificate);
	}

	public RemoteDesktopConfig withoutSecret() {
		return encryptedSecret == null ? this
				: new RemoteDesktopConfig(fqdn, protocol, port, exposedPort, username, null, ignoreCertificate);
	}
}
