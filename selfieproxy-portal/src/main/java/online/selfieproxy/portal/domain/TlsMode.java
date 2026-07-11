package online.selfieproxy.portal.domain;

/**
 * The 3 "Advanced settings" connectivity options shown when a Web Application's
 * Local Network protocol is HTTPS. Only relevant for ExposedAppType.WEB_APPLICATION.
 */
public enum TlsMode {

	/** Default & recommended. BoringProxy tls-termination "server". */
	MANAGED("End-to-end encrypted (recommended)",
			"Selfie Proxy automatically creates and renews a signed certificate for you."),

	/** BoringProxy tls-termination "client-tls". */
	BYO_CERT("End-to-end encrypted (you provide the certificate)",
			"You provide the certificate on the web application in the local network."),

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
