package online.selfieproxy.identityprovider.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import online.selfieproxy.identityprovider.domain.IdpSessionService;

/**
 * Landing page boringproxy's /oidc/logout redirects to after clearing its
 * per-domain SSO cookie. This is also where this IdP's own login session
 * (IdpSessionService) is ended -- every domain's /oidc/logout funnels here,
 * so it's the natural single choke point to tell the IdP "this user signed
 * out," ensuring a still-open second app can't silently resurrect the login
 * via a leftover IdP session on its next authorization round trip.
 */
@Controller
public class LogoutController {

	private final IdpSessionService idpSessionService;

	public LogoutController(IdpSessionService idpSessionService) {
		this.idpSessionService = idpSessionService;
	}

	@GetMapping("/logged-out")
	public String loggedOut(@RequestParam(value = "return_to", required = false) String returnTo, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		idpSessionService.invalidate(request, response);
		model.addAttribute("returnTo", returnTo);
		return "logged-out";
	}
}
