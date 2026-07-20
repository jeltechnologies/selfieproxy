package online.selfieproxy.identityprovider.web;

import java.io.IOException;
import java.util.List;

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
import online.selfieproxy.identityprovider.domain.PasswordPolicy;

/**
 * Forces a password change before completing login when
 * AdminUser.mustChangePassword() is set -- reached only via LoginController's
 * redirect, never authorizes a request on its own otherwise. Strength is
 * enforced via PasswordPolicy (shared with InternalUsersController's admin-row
 * change-password handler). A successful change completes the login, so it
 * also starts this IdP's own login session (IdpSessionService) -- see
 * LoginController.
 */
@Controller
public class ChangePasswordController {

	private final AuthorizationService authorizationService;
	private final AdminUserStore adminUserStore;
	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicy passwordPolicy;
	private final IdpSessionService idpSessionService;

	public ChangePasswordController(AuthorizationService authorizationService, AdminUserStore adminUserStore,
			PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, IdpSessionService idpSessionService) {
		this.authorizationService = authorizationService;
		this.adminUserStore = adminUserStore;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.idpSessionService = idpSessionService;
	}

	@GetMapping("/change-password")
	public String changePasswordPage(@RequestParam("authz_id") String authzId, Model model,
			HttpServletResponse response) throws IOException {
		if (authorizationService.get(authzId) == null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown or expired authorization request.");
			return null;
		}
		model.addAttribute("authzId", authzId);
		model.addAttribute("username", adminUserStore.load().username());
		return "change-password";
	}

	@PostMapping("/change-password")
	public String changePassword(@RequestParam String username, @RequestParam String newPassword,
			@RequestParam String confirmNewPassword, @RequestParam("authz_id") String authzId, Model model,
			HttpServletResponse response) throws IOException {
		if (authorizationService.get(authzId) == null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown or expired authorization request.");
			return null;
		}
		model.addAttribute("authzId", authzId);

		AdminUser adminUser = adminUserStore.load();

		String trimmedUsername = username.trim();
		if (trimmedUsername.isEmpty()) {
			model.addAttribute("username", adminUser.username());
			model.addAttribute("errors", List.of("Username must not be blank."));
			return "change-password";
		}
		model.addAttribute("username", trimmedUsername);

		List<String> errors = passwordPolicy.validate(newPassword, confirmNewPassword, adminUser);
		if (!errors.isEmpty()) {
			model.addAttribute("errors", errors);
			return "change-password";
		}

		adminUserStore.save(new AdminUser(trimmedUsername, passwordEncoder.encode(newPassword), false));
		idpSessionService.startSession(response, trimmedUsername, true);
		authorizationService.markAuthenticated(authzId, trimmedUsername, true);
		return "redirect:/authorize?authz_id=" + authzId;
	}
}
