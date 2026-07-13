package online.selfieproxy.portal.config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Injects accountUrl into every page's model so fragments/layout.html's
 * topbar fragment can link into selfieproxy-identity-provider's self-service
 * account page without every controller having to set it explicitly --
 * fragment params supplement the ambient model, they don't replace it. Left
 * null when an external IdP is configured (OidcProperties.isExternal()),
 * since changing credentials on the bundled IdP would have no effect on the
 * real ones in that case -- the topbar hides the link when this is null.
 */
@ControllerAdvice
public class GlobalModelAttributes {

	private final BoringProxyProperties properties;
	private final OidcProperties oidcProperties;

	public GlobalModelAttributes(BoringProxyProperties properties, OidcProperties oidcProperties) {
		this.properties = properties;
		this.oidcProperties = oidcProperties;
	}

	@ModelAttribute("accountUrl")
	public String accountUrl(HttpServletRequest request) {
		if (oidcProperties.isExternal()) {
			return null;
		}
		// server.forward-headers-strategy=native already corrects scheme/serverName
		// from the request boringproxy proxied in, same as LogoutController.
		String origin = request.getScheme() + "://" + request.getServerName() + "/";
		String returnTo = URLEncoder.encode(origin, StandardCharsets.UTF_8);
		return "https://" + properties.authDomain() + "/account?return_to=" + returnTo;
	}
}
