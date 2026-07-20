package online.selfieproxy.portal.config;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Injects showUsersLink into every page's model so fragments/layout.html's
 * topbar fragment can conditionally show the in-portal Users page link.
 * False when an external IdP is configured (OidcProperties.isExternal()):
 * Selfie Proxy no longer controls who can authenticate at all in that case,
 * so there's no Users list to manage. The admin's own username/password used
 * to be changed via a separate self-service `/account` page (accountUrl,
 * since removed) -- that's now folded into the Users page's admin row
 * (edit / change password) instead of living as its own topbar entry.
 */
@ControllerAdvice
public class GlobalModelAttributes {

	private final OidcProperties oidcProperties;

	public GlobalModelAttributes(OidcProperties oidcProperties) {
		this.oidcProperties = oidcProperties;
	}

	@ModelAttribute("showUsersLink")
	public boolean showUsersLink() {
		return !oidcProperties.isExternal();
	}
}
