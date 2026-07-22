package online.selfieproxy.portal.domain;

/**
 * Selfie Proxy's own model of a published app, mapped to/from a BoringProxy
 * "Tunnel" by {@link TunnelMapper}. Deliberately avoids BoringProxy's own
 * vocabulary (Client/Tunnel) in field names, per selfieproxy-portal/CLAUDE.md.
 *
 * @param subdomain    the app's Selfie Proxy identifier -- the label suffixed with {@link #domain} to form the FQDN
 * @param name         only meaningful when type is NETWORK_SERVICE -- a free-text label; not unique, not part of the domain
 * @param exposedPort  only meaningful when type is NETWORK_SERVICE -- the internet-facing port for RAW_TCP mode, or the boringproxy-assigned tunnel port for SSH/RDP/VNC mode (never internet-facing, dialed by selfieproxy-remote-console instead)
 * @param protocol     only meaningful when type is WEB_APPLICATION (Network Service is always TCP)
 * @param tlsMode      only set when type is WEB_APPLICATION and protocol is HTTPS
 * @param ssoProtected whether boringproxy gates this app behind the configured OIDC issuer; only ever true when {@link #canProtectWithSso()} holds, since boringproxy only ever parses HTTP (and so can enforce single sign on) for "server"-termination tunnels -- see TlsMode.MANAGED
 * @param domain       which registered domain (the primary domain or a secondary one, see DomainService) this app is exposed on -- always the primary domain for SSH/RDP/VNC mode, since there's no public FQDN concept for them
 * @param mode         only meaningful when type is NETWORK_SERVICE -- see NetworkServiceMode
 * @param username     only meaningful for SSH/RDP/VNC mode -- nullable, VNC often has none
 * @param encryptedSecret only meaningful for SSH/RDP/VNC mode -- AES-GCM ciphertext (NetworkServiceCredentialCipher), null when no credential has been entered yet (eg. after a configuration import). Always a password -- there is no private-key auth option.
 * @param ignoreCertificate only meaningful for RDP/VNC mode
 */
public record ExposedApp(
		String subdomain,
		String name,
		String homelabName,
		ExposedAppType type,
		Protocol protocol,
		String host,
		int port,
		Integer exposedPort,
		TlsMode tlsMode,
		boolean ssoProtected,
		String domain,
		NetworkServiceMode mode,
		String username,
		String encryptedSecret,
		boolean ignoreCertificate) {

	public String fqdn() {
		return subdomain + "." + domain;
	}

	public boolean isWebApplication() {
		return type == ExposedAppType.WEB_APPLICATION;
	}

	public boolean isNetworkService() {
		return type == ExposedAppType.NETWORK_SERVICE;
	}

	/** Never null; defaults RAW_TCP for a Network Service that predates this field, or for a fresh app that hasn't set one. */
	public NetworkServiceMode effectiveMode() {
		return mode != null ? mode : NetworkServiceMode.RAW_TCP;
	}

	/** True for the SSH/RDP/VNC modes -- never internet-reachable, reached through a Connect action instead of a public URL. */
	public boolean isRemoteAccessMode() {
		return isNetworkService() && effectiveMode() != NetworkServiceMode.RAW_TCP;
	}

	public boolean showsAdvancedSettings() {
		return isWebApplication() && protocol == Protocol.HTTPS;
	}

	public TlsMode effectiveTlsMode() {
		return tlsMode != null ? tlsMode : TlsMode.MANAGED;
	}

	/**
	 * True whenever the tunnel ends up "server"-terminated -- the only TLS-termination mode boringproxy
	 * itself HTTP-parses, so the only one it can gate with single sign on. That's always true for a plain HTTP
	 * homelab app (Selfie Proxy still terminates the public TLS connection itself, see TunnelMapper),
	 * and true for an HTTPS homelab app only under "Server HTTPS" (TlsMode.MANAGED) -- BYO_CERT/
	 * HOP_BY_HOP are HTTPS too but never HTTP-parsed at the server, so single sign on can't be enforced for them.
	 */
	public boolean canProtectWithSso() {
		return isWebApplication() && (protocol == Protocol.HTTP || effectiveTlsMode() == TlsMode.MANAGED);
	}

	/** Same record with encryptedSecret cleared -- used when building a configuration export (see BackupService). */
	public ExposedApp withoutSecret() {
		return encryptedSecret == null ? this
				: new ExposedApp(subdomain, name, homelabName, type, protocol, host, port, exposedPort, tlsMode,
						ssoProtected, domain, mode, username, null, ignoreCertificate);
	}

	/** Same record with encryptedSecret replaced -- used when a credential is entered on first Connect (see ConsoleConnectController). */
	public ExposedApp withEncryptedSecret(String newEncryptedSecret) {
		return new ExposedApp(subdomain, name, homelabName, type, protocol, host, port, exposedPort, tlsMode,
				ssoProtected, domain, mode, username, newEncryptedSecret, ignoreCertificate);
	}

	/**
	 * Same record with exposedPort replaced -- an SSH/RDP/VNC-mode Network Service always submits
	 * exposedPort null (boringproxy auto-assigns it), so the actual assigned port has to be
	 * captured from the CreateTunnelRequest's response and folded back in before saving, or
	 * selfieproxy-remote-console (which reads exposedPort straight off exposed-apps.json, never
	 * from boringproxy) ends up dialing port 0 -- see ExposedAppController.create/update and
	 * BackupService.doApplyRestore.
	 */
	public ExposedApp withExposedPort(Integer newExposedPort) {
		return new ExposedApp(subdomain, name, homelabName, type, protocol, host, port, newExposedPort, tlsMode,
				ssoProtected, domain, mode, username, encryptedSecret, ignoreCertificate);
	}
}
