package online.selfieproxy.portal.domain;

/**
 * The 3 connectivity options for a Web Application whose Homelab protocol is HTTPS. The portal no
 * longer exposes a picker for this -- every Web Application is always MANAGED
 * (ExposedAppController.toExposedApp always passes tlsMode=null, and effectiveTlsMode() defaults
 * null to MANAGED) and the "Advanced settings" UI that let a user pick BYO_CERT/HOP_BY_HOP has
 * been removed from edit-app.html. The enum, TunnelMapper's mapping, and ExposedApp.canProtectWithSso()
 * are kept as-is in case this is reintroduced later. Only relevant for ExposedAppType.WEB_APPLICATION.
 */
public enum TlsMode {

	/** Default & recommended. BoringProxy tls-termination "server". */
	MANAGED("End-to-end encrypted (recommended)",
			"Selfie Proxy automatically creates and renews a signed certificate, and can protect your web application by forcing authentication through Selfieproxy login."),

	/** BoringProxy tls-termination "client-tls". */
	BYO_CERT("End-to-end encrypted (you provide the certificate)",
			"Not supported behind a reverse proxy (e.g. NGINX) in your homelab -- the agent must connect directly to the web application, which provides its own certificate and handles authentication itself."),

	/** BoringProxy tls-termination "server-tls". */
	HOP_BY_HOP("Hop-by-hop encryption (compatibility mode)",
			"For web applications that break on normal HTTPS. Selfie Proxy automatically creates and renews a signed certificate.");

	private final String label;
	private final String description;

	TlsMode(String label, String description) {
		this.label = label;
		this.description = description;
	}

	public String label() {
		return label;
	}

	public String description() {
		return description;
	}
}
