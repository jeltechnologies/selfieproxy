package online.selfieproxy.portal.session;

import jakarta.servlet.http.HttpSession;

/** Reads/writes the PortalSession kept in the servlet HttpSession under one fixed key. */
public final class PortalSessions {

	private static final String ATTRIBUTE = "portalSession";

	private PortalSessions() {
	}

	public static PortalSession get(HttpSession session) {
		return session == null ? null : (PortalSession) session.getAttribute(ATTRIBUTE);
	}

	public static void set(HttpSession session, PortalSession portalSession) {
		session.setAttribute(ATTRIBUTE, portalSession);
	}

	public static void clear(HttpSession session) {
		if (session != null) {
			session.removeAttribute(ATTRIBUTE);
		}
	}
}
