package online.selfieproxy.portal.session;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Redirects to /login for any page that isn't already the login page when no PortalSession exists. */
public class SessionInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		if (PortalSessions.get(request.getSession(false)) != null) {
			return true;
		}
		response.sendRedirect(request.getContextPath() + "/login");
		return false;
	}
}
