package online.selfieproxy.portal.domain;

/**
 * Branding for selfieproxy-identity-provider's login/change-password/logged-out pages -- only
 * meaningful when the bundled IdP is actually authenticating users (see OidcProperties.isExternal()).
 * Written here, read by that module's own mirrored, read-only LoginScreenSettingsStore over the
 * shared /data volume -- same idiom as ThemeStore/TerminalSettingsStore.
 *
 * backgroundLightFilename/backgroundDarkFilename name a file under
 * data/selfieproxy/login-screen/ (e.g. "background-light.png"), or are null when no image has
 * been uploaded for that mode -- managed only by LoginScreenImageStore, never by
 * LoginScreenController's own text-field save.
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
