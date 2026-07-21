package online.selfieproxy.identityprovider.web;

import java.io.IOException;
import java.util.Optional;

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
import online.selfieproxy.identityprovider.domain.LoginAttemptTracker;
import online.selfieproxy.identityprovider.domain.User;
import online.selfieproxy.identityprovider.domain.UserStore;

/**
 * Selfie Proxy's own end-user login: the admin's credentials are checked
 * first, against the persisted AdminUser record in AdminUserStore (itself
 * seeded once from ADMIN_PORTAL_USERNAME/ADMIN_PORTAL_BOOTSTRAP_PASSWORD on
 * first boot -- see AdminAuthProperties); if that fails, a regular User
 * (UserStore) with the same credentials is tried before giving up. A
 * successful admin login with AdminUser.mustChangePassword() still set is
 * redirected into ChangePasswordController instead of being authorized,
 * given a pending authorization request (authz_id, see
 * AuthorizationService) to complete instead of a servlet session -- regular
 * Users have no such forced-change flow, since only an admin operator (via
 * selfieproxy-portal's Users page, which manages User/AdminUser records
 * through InternalUsersController) ever sets their password. A successful login of either
 * kind also starts this IdP's own login session (IdpSessionService), so any
 * other single-sign-on-protected domain's authorization round trip is silent from here
 * on -- but boringproxy's HandleCallback still re-checks the ID token's
 * is_admin claim on every fresh domain hop, so a regular User's silent
 * re-auth into the portal domain is rejected there, not here.
 *
 * Every failed attempt is recorded in LoginAttemptTracker, which backs off
 * (not locks out) further attempts for that username -- see its own doc
 * comment for why a hard lockout isn't used here.
 */
@Controller
public class LoginController {

	private final AuthorizationService authorizationService;
	private final AdminUserStore adminUserStore;
	private final UserStore userStore;
	private final PasswordEncoder passwordEncoder;
	private final IdpSessionService idpSessionService;
	private final LoginAttemptTracker loginAttemptTracker;

	public LoginController(AuthorizationService authorizationService, AdminUserStore adminUserStore,
			UserStore userStore, PasswordEncoder passwordEncoder, IdpSessionService idpSessionService,
			LoginAttemptTracker loginAttemptTracker) {
		this.authorizationService = authorizationService;
		this.adminUserStore = adminUserStore;
		this.userStore = userStore;
		this.passwordEncoder = passwordEncoder;
		this.idpSessionService = idpSessionService;
		this.loginAttemptTracker = loginAttemptTracker;
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

		if (loginAttemptTracker.secondsUntilNextAttempt(username) > 0) {
			model.addAttribute("error", "Too many failed attempts. Try again later.");
			model.addAttribute("authzId", authzId);
			return "login";
		}

		AdminUser adminUser = adminUserStore.load();
		if (username.equals(adminUser.username()) && passwordEncoder.matches(password, adminUser.passwordHash())) {
			loginAttemptTracker.recordSuccess(username);
			if (adminUser.mustChangePassword()) {
				return "redirect:/change-password?authz_id=" + authzId;
			}
			idpSessionService.startSession(response, username, true);
			authorizationService.markAuthenticated(authzId, username, true);
			return "redirect:/authorize?authz_id=" + authzId;
		}

		Optional<User> user = userStore.find(username);
		if (user.isPresent() && passwordEncoder.matches(password, user.get().passwordHash())) {
			loginAttemptTracker.recordSuccess(username);
			idpSessionService.startSession(response, username, false);
			authorizationService.markAuthenticated(authzId, username, false);
			return "redirect:/authorize?authz_id=" + authzId;
		}

		loginAttemptTracker.recordFailure(username);
		model.addAttribute("error", "Invalid username or password.");
		model.addAttribute("authzId", authzId);
		return "login";
	}
}
