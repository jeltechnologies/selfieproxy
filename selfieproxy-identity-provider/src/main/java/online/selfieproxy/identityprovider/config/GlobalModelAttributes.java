package online.selfieproxy.identityprovider.config;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import online.selfieproxy.identityprovider.domain.ThemeStore;

/**
 * Injects theme into every page's model -- the persisted Light/Dark/Dracula setting
 * (ThemeStore, a read-only mirror of selfieproxy-portal's own setting), set as every
 * template's <html data-theme="..."> attribute so the login/change-password/logged-out
 * pages honor the same appearance chosen in the portal's Settings > Appearance page.
 */
@ControllerAdvice
public class GlobalModelAttributes {

	private final ThemeStore themeStore;

	public GlobalModelAttributes(ThemeStore themeStore) {
		this.themeStore = themeStore;
	}

	@ModelAttribute("theme")
	public String theme() {
		return themeStore.load().id();
	}
}
