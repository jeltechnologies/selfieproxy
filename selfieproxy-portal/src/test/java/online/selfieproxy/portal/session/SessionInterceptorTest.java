package online.selfieproxy.portal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SessionInterceptorTest {

	private static final String SSO_VERIFIED_HEADER = "X-Selfieproxy-Sso-Verified";

	private final SessionInterceptor interceptor = new SessionInterceptor();

	@Test
	void passesThroughWhenPortalSessionAlreadyEstablished() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		PortalSessions.set(request.getSession(true), new PortalSession("admin", true));
		MockHttpServletResponse response = new MockHttpServletResponse();

		boolean proceed = interceptor.preHandle(request, response, new Object());

		assertTrue(proceed);
	}

	@Test
	void establishesPortalSessionOnFirstSightOfSsoVerifiedHeader() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(SSO_VERIFIED_HEADER, "true");
		MockHttpServletResponse response = new MockHttpServletResponse();

		boolean proceed = interceptor.preHandle(request, response, new Object());

		assertTrue(proceed);
		PortalSession session = PortalSessions.get(request.getSession(false));
		assertEquals("admin", session.owner());
		assertTrue(session.isAdmin());
	}

	@Test
	void rejectsRequestsWithNeitherAnExistingSessionNorTheHeader() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		boolean proceed = interceptor.preHandle(request, response, new Object());

		assertFalse(proceed);
		assertEquals(403, response.getStatus());
	}
}
