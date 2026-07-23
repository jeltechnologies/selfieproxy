package online.selfieproxy.portal.config;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import online.selfieproxy.portal.domain.ThemeStore;

/**
 * Injects showUsersLink and theme into every page's model. showUsersLink lets
 * fragments/layout.html's topbar fragment conditionally show the in-portal
 * Users page link -- false when an external IdP is configured
 * (OidcProperties.isExternal()): Selfie Proxy no longer controls who can
 * authenticate at all in that case, so there's no Users list to manage. The
 * admin's own username/password used to be changed via a separate
 * self-service `/account` page (accountUrl, since removed) -- that's now
 * folded into the Users page's admin row (edit / change password) instead of
 * living as its own topbar entry.
 *
 * theme is the persisted Light/Dark/Dracula setting (ThemeStore), set as
 * every template's <html data-theme="..."> attribute -- see AppearanceController.
 */
@ControllerAdvice
public class GlobalModelAttributes {

	private final OidcProperties oidcProperties;
	private final ThemeStore themeStore;

	public GlobalModelAttributes(OidcProperties oidcProperties, ThemeStore themeStore) {
		this.oidcProperties = oidcProperties;
		this.themeStore = themeStore;
	}

	@ModelAttribute("showUsersLink")
	public boolean showUsersLink() {
		return !oidcProperties.isExternal();
	}

	@ModelAttribute("theme")
	public String theme() {
		return themeStore.load().id();
	}
}
