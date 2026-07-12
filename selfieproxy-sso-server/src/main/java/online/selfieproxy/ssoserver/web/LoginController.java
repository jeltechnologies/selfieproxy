package online.selfieproxy.ssoserver.web;

import java.io.IOException;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletResponse;

import online.selfieproxy.ssoserver.config.AdminAuthProperties;
import online.selfieproxy.ssoserver.domain.AuthorizationService;

/**
 * Selfie Proxy's own end-user login, checked against ADMIN_PORTAL_USERNAME /
 * ADMIN_PORTAL_PASSWORD -- the plaintext check moved verbatim from
 * selfieproxy-portal's former LoginController, now given a pending
 * authorization request (authz_id, see AuthorizationService) to complete
 * instead of a servlet session.
 */
@Controller
public class LoginController {

	private final AdminAuthProperties authProperties;
	private final AuthorizationService authorizationService;

	public LoginController(AdminAuthProperties authProperties, AuthorizationService authorizationService) {
		this.authProperties = authProperties;
		this.authorizationService = authorizationService;
	}

	@GetMapping("/login")
	public String loginPage(@RequestParam("authz_id") String authzId, Model model, HttpServletResponse response) throws IOException {
		if (authorizationService.get(authzId) == null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown or expired authorization request.");
			return null;
		}
		model.addAttribute("authzId", authzId);
		return "login";
	}

	@PostMapping("/login")
	public String login(@RequestParam String username, @RequestParam String password,
			@RequestParam("authz_id") String authzId, Model model, HttpServletResponse response) throws IOException {
		if (authorizationService.get(authzId) == null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown or expired authorization request.");
			return null;
		}
		if (username.equals(authProperties.username()) && password.equals(authProperties.password())) {
			authorizationService.markAuthenticated(authzId);
			return "redirect:/authorize?authz_id=" + authzId;
		}
		model.addAttribute("error", "Invalid username or password.");
		model.addAttribute("authzId", authzId);
		return "login";
	}
}
