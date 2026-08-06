package online.selfieproxy.identityprovider.config;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import online.selfieproxy.identityprovider.domain.LoginScreenSettings;
import online.selfieproxy.identityprovider.domain.LoginScreenSettingsStore;
import online.selfieproxy.identityprovider.domain.ThemeStore;

/**
 * Injects theme, loginScreenSettings, and loginScreenBackgroundUrl into every page's model.
 *
 * theme is the persisted Light/Dark/Dracula setting (ThemeStore, a read-only mirror of
 * selfieproxy-portal's own setting), set as every template's <html data-theme="..."> attribute
 * so the login/change-password/logged-out pages honor the same appearance chosen in the portal's
 * Settings > Appearance page.
 *
 * loginScreenSettings is the branding chosen on the portal's own Settings > Login screen page
 * (LoginScreenSettingsStore, another read-only mirror) -- title/slogan/labels/logo-on-off/
 * footer-on-off. loginScreenBackgroundUrl resolves theme + loginScreenSettings down to a single
 * URL (or null) so templates don't each have to duplicate the "which file for which theme"
 * fallback logic -- backed by LoginScreenAssetController, which streams the actual file bytes.
 */
@ControllerAdvice
public class GlobalModelAttributes {

	private final ThemeStore themeStore;
	private final LoginScreenSettingsStore loginScreenSettingsStore;

	public GlobalModelAttributes(ThemeStore themeStore, LoginScreenSettingsStore loginScreenSettingsStore) {
		this.themeStore = themeStore;
		this.loginScreenSettingsStore = loginScreenSettingsStore;
	}

	@ModelAttribute("theme")
	public String theme() {
		return themeStore.load().id();
	}

	@ModelAttribute("loginScreenSettings")
	public LoginScreenSettings loginScreenSettings() {
		return loginScreenSettingsStore.load();
	}

	@ModelAttribute("loginScreenBackgroundUrl")
	public String loginScreenBackgroundUrl() {
		LoginScreenSettings settings = loginScreenSettingsStore.load();
		boolean dark = "dark".equals(theme());
		String filename = dark ? settings.backgroundDarkFilename() : settings.backgroundLightFilename();
		if (filename == null) {
			return null;
		}
		return "/login-screen/background/" + (dark ? "dark" : "light");
	}
}
