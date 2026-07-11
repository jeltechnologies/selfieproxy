package online.selfieproxy.portal.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import online.selfieproxy.portal.config.AdminPortalAuthProperties;
import online.selfieproxy.portal.session.PortalSession;
import online.selfieproxy.portal.session.PortalSessions;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Selfie Proxy's own end-user login, checked against ADMIN_PORTAL_USERNAME /
 * ADMIN_PORTAL_PASSWORD from .env -- unrelated to boringproxy's own
 * user/token system. Placeholder ahead of a future Keycloak/Authentik SSO
 * integration; only this class needs to change when that happens.
 */
@Controller
public class LoginController {

	private final AdminPortalAuthProperties authProperties;

	public LoginController(AdminPortalAuthProperties authProperties) {
		this.authProperties = authProperties;
	}

	@GetMapping("/login")
	public String loginPage() {
		return "login";
	}

	@PostMapping("/login")
	public String login(@RequestParam String username, @RequestParam String password,
			HttpServletRequest request, Model model) {
		if (username.equals(authProperties.username()) && password.equals(authProperties.password())) {
			PortalSessions.set(request.getSession(true), new PortalSession(username, true));
			return "redirect:/";
		}
		model.addAttribute("error", "Invalid username or password.");
		return "login";
	}

	@PostMapping("/logout")
	public String logout(HttpServletRequest request) {
		PortalSessions.clear(request.getSession(false));
		return "redirect:/login";
	}
}
