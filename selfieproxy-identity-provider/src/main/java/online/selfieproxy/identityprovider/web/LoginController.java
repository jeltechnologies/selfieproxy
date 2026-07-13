package online.selfieproxy.identityprovider.web;

import java.io.IOException;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletResponse;

import online.selfieproxy.identityprovider.domain.AdminUser;
import online.selfieproxy.identityprovider.domain.AdminUserStore;
import online.selfieproxy.identityprovider.domain.AuthorizationService;
import online.selfieproxy.identityprovider.domain.IdpSessionService;

/**
 * Selfie Proxy's own end-user login: username and password both checked
 * against the persisted AdminUser record in AdminUserStore (itself seeded
 * once from ADMIN_PORTAL_USERNAME/ADMIN_PORTAL_BOOTSTRAP_PASSWORD on first
 * boot -- see AdminAuthProperties). A successful login with
 * AdminUser.mustChangePassword() still set is redirected into
 * ChangePasswordController instead of being authorized, given a pending
 * authorization request (authz_id, see AuthorizationService) to complete
 * instead of a servlet session. A successful login also starts this IdP's
 * own login session (IdpSessionService), so any other SSO-protected
 * domain's authorization round trip is silent from here on.
 */
@Controller
public class LoginController {

	private final AuthorizationService authorizationService;
	private final AdminUserStore adminUserStore;
	private final PasswordEncoder passwordEncoder;
	private final IdpSessionService idpSessionService;

	public LoginController(AuthorizationService authorizationService,
			AdminUserStore adminUserStore, PasswordEncoder passwordEncoder, IdpSessionService idpSessionService) {
		this.authorizationService = authorizationService;
		this.adminUserStore = adminUserStore;
		this.passwordEncoder = passwordEncoder;
		this.idpSessionService = idpSessionService;
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
		AdminUser adminUser = adminUserStore.load();
		if (username.equals(adminUser.username()) && passwordEncoder.matches(password, adminUser.passwordHash())) {
			if (adminUser.mustChangePassword()) {
				return "redirect:/change-password?authz_id=" + authzId;
			}
			idpSessionService.startSession(response);
			authorizationService.markAuthenticated(authzId);
			return "redirect:/authorize?authz_id=" + authzId;
		}
		model.addAttribute("error", "Invalid username or password.");
		model.addAttribute("authzId", authzId);
		return "login";
	}
}
