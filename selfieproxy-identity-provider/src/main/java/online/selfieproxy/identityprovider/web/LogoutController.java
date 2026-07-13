package online.selfieproxy.identityprovider.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Landing page boringproxy's /oidc/logout redirects to after clearing its
 * SSO cookie -- this IdP has no session of its own to end (see
 * AuthorizationService), so there is nothing to invalidate here; this is
 * purely a confirmation page plus a way back into whichever app the user
 * logged out of.
 */
@Controller
public class LogoutController {

	@GetMapping("/logged-out")
	public String loggedOut(@RequestParam(value = "return_to", required = false) String returnTo, Model model) {
		model.addAttribute("returnTo", returnTo);
		return "logged-out";
	}
}
