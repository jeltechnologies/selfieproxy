package online.selfieproxy.portal.config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Injects usersUrl into every page's model so fragments/layout.html's
 * topbar fragment can link into selfieproxy-identity-provider's Users list
 * without every controller having to set it explicitly -- fragment params
 * supplement the ambient model, they don't replace it. Left null when an
 * external IdP is configured (OidcProperties.isExternal()): Selfie Proxy no
 * longer controls who can authenticate at all in that case, so there's no
 * Users list to manage -- the topbar hides the link when it's null. The
 * admin's own username/password used to be changed via a separate
 * self-service `/account` page (accountUrl, since removed) -- that's now
 * folded into the Users page's admin row (edit / change password) instead
 * of living as its own topbar entry.
 */
@ControllerAdvice
public class GlobalModelAttributes {

	private final BoringProxyProperties properties;
	private final OidcProperties oidcProperties;

	public GlobalModelAttributes(BoringProxyProperties properties, OidcProperties oidcProperties) {
		this.properties = properties;
		this.oidcProperties = oidcProperties;
	}

	@ModelAttribute("usersUrl")
	public String usersUrl(HttpServletRequest request) {
		if (oidcProperties.isExternal()) {
			return null;
		}
		String origin = request.getScheme() + "://" + request.getServerName() + "/";
		String returnTo = URLEncoder.encode(origin, StandardCharsets.UTF_8);
		return "https://" + properties.authDomain() + "/users?return_to=" + returnTo;
	}
}
