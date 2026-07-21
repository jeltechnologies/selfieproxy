package online.selfieproxy.identityprovider.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import online.selfieproxy.identityprovider.config.SsoProperties;
import online.selfieproxy.identityprovider.domain.IdpSessionService;

/**
 * Landing page boringproxy's /oidc/logout redirects to after clearing its
 * per-domain single sign on cookie. This is also where this IdP's own login session
 * (IdpSessionService) is ended -- every domain's /oidc/logout funnels here,
 * so it's the natural single choke point to tell the IdP "this user signed
 * out," ensuring a still-open second app can't silently resurrect the login
 * via a leftover IdP session on its next authorization round trip.
 */
@Controller
public class LogoutController {

	private final IdpSessionService idpSessionService;
	private final SsoProperties properties;

	public LogoutController(IdpSessionService idpSessionService, SsoProperties properties) {
		this.idpSessionService = idpSessionService;
		this.properties = properties;
	}

	@GetMapping("/logged-out")
	public String loggedOut(@RequestParam(value = "return_to", required = false) String returnTo, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		idpSessionService.invalidate(request, response);
		model.addAttribute("returnTo", sanitizeReturnTo(returnTo));
		return "logged-out";
	}

	/**
	 * This endpoint is public and unauthenticated, so return_to is entirely
	 * attacker-controlled -- anyone can link straight to
	 * https://<auth-domain>/logged-out?return_to=https://evil.example and get
	 * a trusted-looking selfieproxy page with a "Log in again" button that
	 * actually leads offsite. Only an https URL whose host is this
	 * deployment's own primaryDomain (or a subdomain of it -- covers the
	 * portal/proxylistener/auth domains and every default-case exposed
	 * app/Local Website) is allowed through; anything else, including a
	 * registered secondary/custom domain outside primaryDomain, is dropped
	 * rather than shown, matching the template's existing
	 * th:if="${returnTo != null}" fallback (no button).
	 */
	private String sanitizeReturnTo(String returnTo) {
		String primaryDomain = properties.primaryDomain();
		if (returnTo == null || primaryDomain == null || primaryDomain.isBlank()) {
			return null;
		}
		URI uri;
		try {
			uri = new URI(returnTo);
		} catch (URISyntaxException e) {
			return null;
		}
		String host = uri.getHost();
		if (host == null || !"https".equalsIgnoreCase(uri.getScheme())) {
			return null;
		}
		String lowerHost = host.toLowerCase(Locale.ROOT);
		String lowerPrimaryDomain = primaryDomain.toLowerCase(Locale.ROOT);
		if (!lowerHost.equals(lowerPrimaryDomain) && !lowerHost.endsWith("." + lowerPrimaryDomain)) {
			return null;
		}
		return returnTo;
	}
}
