package online.selfieproxy.identityprovider.domain;

/**
 * Mirrors selfieproxy-portal's own LoginScreenSettings exactly (no shared Java dependency between
 * modules, same as every other mirrored type in this repo) -- branding for this module's own
 * login/change-password/logged-out pages. Written only by the portal's LoginScreenController;
 * read here via LoginScreenSettingsStore.
 */
public record LoginScreenSettings(
		boolean logoEnabled,
		String title,
		String slogan,
		String usernameLabel,
		String passwordLabel,
		String loginButtonLabel,
		boolean footerEnabled,
		String backgroundLightFilename,
		String backgroundDarkFilename) {

	public static final String DEFAULT_TITLE = "Selfie Proxy";
	public static final String DEFAULT_SLOGAN = "Self host the homelab to the internet - simple and sweet";
	public static final String DEFAULT_USERNAME_LABEL = "Username";
	public static final String DEFAULT_PASSWORD_LABEL = "Password";
	public static final String DEFAULT_LOGIN_BUTTON_LABEL = "Log in";

	public static LoginScreenSettings defaults() {
		return new LoginScreenSettings(true, DEFAULT_TITLE, DEFAULT_SLOGAN, DEFAULT_USERNAME_LABEL,
				DEFAULT_PASSWORD_LABEL, DEFAULT_LOGIN_BUTTON_LABEL, true, null, null);
	}
}
