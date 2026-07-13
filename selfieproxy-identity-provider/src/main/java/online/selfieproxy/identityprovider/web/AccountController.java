package online.selfieproxy.identityprovider.web;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import online.selfieproxy.identityprovider.domain.AdminUser;
import online.selfieproxy.identityprovider.domain.AdminUserStore;
import online.selfieproxy.identityprovider.domain.PasswordPolicy;

/**
 * Self-service username/password change, reached directly from the portal's
 * user menu. Not gated behind a live OIDC authorization (see
 * AuthorizationService's stateless design) -- re-entering the current
 * password on each submit stands in for a session check. A single form
 * covers both fields: the password ones are optional (blank means "keep the
 * current password"), so one submit can change just the username, just the
 * password, or both.
 */
@Controller
public class AccountController {

	private final AdminUserStore adminUserStore;
	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicy passwordPolicy;

	public AccountController(AdminUserStore adminUserStore, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy) {
		this.adminUserStore = adminUserStore;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
	}

	@GetMapping("/account")
	public String accountPage(@RequestParam(value = "return_to", required = false) String returnTo, Model model) {
		model.addAttribute("returnTo", returnTo);
		model.addAttribute("username", adminUserStore.load().username());
		return "account";
	}

	@PostMapping("/account")
	public String updateAccount(@RequestParam String username, @RequestParam String currentPassword,
			@RequestParam(required = false, defaultValue = "") String newPassword,
			@RequestParam(required = false, defaultValue = "") String confirmNewPassword,
			@RequestParam(value = "return_to", required = false) String returnTo, Model model) {
		model.addAttribute("returnTo", returnTo);
		AdminUser adminUser = adminUserStore.load();

		if (!passwordEncoder.matches(currentPassword, adminUser.passwordHash())) {
			model.addAttribute("username", adminUser.username());
			model.addAttribute("errors", List.of("Current password is incorrect."));
			return "account";
		}

		String trimmedUsername = username.trim();
		if (trimmedUsername.isEmpty()) {
			model.addAttribute("username", adminUser.username());
			model.addAttribute("errors", List.of("Username must not be blank."));
			return "account";
		}

		String passwordHash = adminUser.passwordHash();
		boolean mustChangePassword = adminUser.mustChangePassword();
		boolean changingPassword = !newPassword.isEmpty() || !confirmNewPassword.isEmpty();
		if (changingPassword) {
			List<String> errors = passwordPolicy.validate(newPassword, confirmNewPassword, adminUser);
			if (!errors.isEmpty()) {
				model.addAttribute("username", adminUser.username());
				model.addAttribute("errors", errors);
				return "account";
			}
			passwordHash = passwordEncoder.encode(newPassword);
			mustChangePassword = false;
		}

		adminUserStore.save(new AdminUser(trimmedUsername, passwordHash, mustChangePassword));
		if (returnTo != null && !returnTo.isBlank()) {
			return "redirect:" + returnTo;
		}
		model.addAttribute("username", trimmedUsername);
		model.addAttribute("success", "Account settings updated.");
		return "account";
	}
}
