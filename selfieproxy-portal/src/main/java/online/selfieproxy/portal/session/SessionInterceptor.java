package online.selfieproxy.portal.session;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The portal no longer checks a password itself -- boringproxy gates every
 * request to the portal domain against the configured OIDC issuer (bundled
 * selfieproxy-identity-provider by default) before it ever reaches this container,
 * setting X-Selfieproxy-Sso-Verified on the proxied request once it does.
 * Trustworthy because boringproxy is the one adding it right before
 * proxying, the same trust boundary already relied on for X-Forwarded-*
 * (server.forward-headers-strategy=native). On first sight of the header
 * for a given HttpSession, establishes a PortalSession so the rest of the
 * app can keep reading PortalSessions as before; a request reaching here
 * without either an existing PortalSession or the header should be
 * unreachable in practice (it would mean something bypassed boringproxy
 * entirely) and is rejected outright rather than redirected anywhere, since
 * there's no login page left to redirect to.
 */
public class SessionInterceptor implements HandlerInterceptor {

	private static final String SSO_VERIFIED_HEADER = "X-Selfieproxy-Sso-Verified";
	private static final String OWNER = "admin";

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		if (PortalSessions.get(request.getSession(false)) != null) {
			return true;
		}
		if ("true".equals(request.getHeader(SSO_VERIFIED_HEADER))) {
			PortalSessions.set(request.getSession(true), new PortalSession(OWNER, true));
			return true;
		}
		response.sendError(HttpServletResponse.SC_FORBIDDEN,
				"This request did not come through Selfie Proxy's single sign on gate.");
		return false;
	}
}
