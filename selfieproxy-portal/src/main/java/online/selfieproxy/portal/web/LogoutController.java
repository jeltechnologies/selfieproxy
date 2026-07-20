package online.selfieproxy.portal.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Ends the portal's own session and hands off to boringproxy's /oidc/logout
 * carve-out to clear the single sign on cookie too -- invalidating only one
 * of the two would leave the other still granting access (see
 * SessionInterceptor, which trusts an existing PortalSession before it even
 * looks at the single sign on header).
 */
@Controller
public class LogoutController {

	@PostMapping("/logout")
	public String logout(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}

		// server.forward-headers-strategy=native already corrects scheme/serverName
		// from the request boringproxy proxied in, so this is the portal's own
		// public origin, not boringproxy's or the portal container's internal one.
		String origin = request.getScheme() + "://" + request.getServerName() + "/";
		return "redirect:/oidc/logout?return_to=" + URLEncoder.encode(origin, StandardCharsets.UTF_8);
	}
}
