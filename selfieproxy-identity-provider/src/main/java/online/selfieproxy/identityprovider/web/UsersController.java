package online.selfieproxy.identityprovider.web;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import online.selfieproxy.identityprovider.domain.AdminUser;
import online.selfieproxy.identityprovider.domain.AdminUserStore;
import online.selfieproxy.identityprovider.domain.IdpSessionService;
import online.selfieproxy.identityprovider.domain.PasswordPolicy;
import online.selfieproxy.identityprovider.domain.User;
import online.selfieproxy.identityprovider.domain.UserStore;

/**
 * Admin-only management of non-admin Users -- reached from the portal's
 * Settings menu (hidden whenever an external IdP is configured, see
 * selfieproxy-portal's GlobalModelAttributes). Every handler requires a
 * still-valid admin IdpSessionService session; a non-admin or absent session
 * gets a flat 403, since there is no login form to redirect into here (this
 * page is only ever linked to from an already-authenticated portal).
 *
 * A regular user's username must never collide with the admin's own
 * (LoginController checks the admin first, so a colliding regular-user
 * record would be permanently unreachable) -- every create/rename path here
 * rejects a username equal to whatever the *other* store currently holds.
 */
@Controller
public class UsersController {

	private final UserStore userStore;
	private final AdminUserStore adminUserStore;
	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicy passwordPolicy;
	private final IdpSessionService idpSessionService;

	public UsersController(UserStore userStore, AdminUserStore adminUserStore, PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy, IdpSessionService idpSessionService) {
		this.userStore = userStore;
		this.adminUserStore = adminUserStore;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.idpSessionService = idpSessionService;
	}

	@GetMapping("/users")
	public String list(@RequestParam(value = "return_to", required = false) String returnTo, Model model,
			HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!requireAdmin(request, response)) {
			return null;
		}
		List<User> sortedUsers = userStore.list().stream().sorted(Comparator.comparing(User::username)).toList();

		model.addAttribute("adminUsername", adminUserStore.load().username());
		model.addAttribute("users", sortedUsers);
		model.addAttribute("returnTo", returnTo);
		return "users";
	}

	@GetMapping("/users/new")
	public String newUserPage(@RequestParam(value = "return_to", required = false) String returnTo, Model model,
			HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!requireAdmin(request, response)) {
			return null;
		}
		model.addAttribute("isNew", true);
		model.addAttribute("isAdminRow", false);
		model.addAttribute("username", "");
		model.addAttribute("returnTo", returnTo);
		return "edit-user";
	}

	@PostMapping("/users/new")
	public String createUser(@RequestParam String username, @RequestParam String password,
			@RequestParam String confirmPassword,
			@RequestParam(value = "return_to", required = false) String returnTo, Model model,
			HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!requireAdmin(request, response)) {
			return null;
		}
		model.addAttribute("isNew", true);
		model.addAttribute("isAdminRow", false);
		model.addAttribute("returnTo", returnTo);

		String trimmedUsername = username.trim();
		model.addAttribute("username", trimmedUsername);

		if (trimmedUsername.isEmpty()) {
			model.addAttribute("errors", List.of("Username must not be blank."));
			return "edit-user";
		}
		if (containsWhitespace(trimmedUsername)) {
			model.addAttribute("errors", List.of("Username must not contain spaces."));
			return "edit-user";
		}
		if (usernameTaken(trimmedUsername, null)) {
			model.addAttribute("errors", List.of("That username is already in use."));
			return "edit-user";
		}

		List<String> errors = passwordPolicy.validate(password, confirmPassword, (String) null);
		if (!errors.isEmpty()) {
			model.addAttribute("errors", errors);
			return "edit-user";
		}

		try {
			userStore.add(new User(trimmedUsername, passwordEncoder.encode(password)));
		} catch (IllegalStateException e) {
			// Lost a race with a concurrent add of the same username -- the
			// usernameTaken() pre-check above already caught the common case.
			model.addAttribute("errors", List.of("That username is already in use."));
			return "edit-user";
		}
		return "redirect:/users" + returnToQuery(returnTo);
	}

	@GetMapping("/users/{username}/edit")
	public String editUserPage(@PathVariable String username,
			@RequestParam(value = "return_to", required = false) String returnTo, Model model,
			HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!requireAdmin(request, response)) {
			return null;
		}
		boolean isAdminRow = username.equals(adminUserStore.load().username());
		if (!isAdminRow && userStore.find(username).isEmpty()) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown user.");
			return null;
		}

		model.addAttribute("isNew", false);
		model.addAttribute("isAdminRow", isAdminRow);
		model.addAttribute("username", username);
		model.addAttribute("returnTo", returnTo);
		return "edit-user";
	}

	@PostMapping("/users/{username}/edit")
	public String updateUser(@PathVariable String username, @RequestParam("username") String newUsername,
			@RequestParam(required = false, defaultValue = "") String currentPassword,
			@RequestParam(required = false, defaultValue = "") String newPassword,
			@RequestParam(required = false, defaultValue = "") String confirmNewPassword,
			@RequestParam(value = "return_to", required = false) String returnTo, Model model,
			HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!requireAdmin(request, response)) {
			return null;
		}
		boolean isAdminRow = username.equals(adminUserStore.load().username());
		if (!isAdminRow && userStore.find(username).isEmpty()) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown user.");
			return null;
		}

		model.addAttribute("isNew", false);
		model.addAttribute("isAdminRow", isAdminRow);
		model.addAttribute("returnTo", returnTo);

		String trimmedUsername = newUsername.trim();
		model.addAttribute("username", trimmedUsername);

		if (trimmedUsername.isEmpty()) {
			model.addAttribute("errors", List.of("Username must not be blank."));
			return "edit-user";
		}
		if (containsWhitespace(trimmedUsername)) {
			model.addAttribute("errors", List.of("Username must not contain spaces."));
			return "edit-user";
		}
		if (!trimmedUsername.equals(username) && usernameTaken(trimmedUsername, isAdminRow ? null : username)) {
			model.addAttribute("errors", List.of("That username is already in use."));
			return "edit-user";
		}

		// Blank new-password fields mean "leave the password as-is" -- the
		// password section on this page is optional, unlike the required
		// fields used when adding a brand-new user.
		boolean changingPassword = !newPassword.isEmpty() || !confirmNewPassword.isEmpty();
		AdminUser adminUser = isAdminRow ? adminUserStore.load() : null;
		if (changingPassword) {
			if (isAdminRow) {
				if (!passwordEncoder.matches(currentPassword, adminUser.passwordHash())) {
					model.addAttribute("errors", List.of("Current password is incorrect."));
					return "edit-user";
				}
				List<String> errors = passwordPolicy.validate(newPassword, confirmNewPassword, adminUser);
				if (!errors.isEmpty()) {
					model.addAttribute("errors", errors);
					return "edit-user";
				}
			} else {
				User user = userStore.find(username).orElseThrow();
				List<String> errors = passwordPolicy.validate(newPassword, confirmNewPassword, user.passwordHash());
				if (!errors.isEmpty()) {
					model.addAttribute("errors", errors);
					return "edit-user";
				}
			}
		}

		if (isAdminRow) {
			String passwordHash = changingPassword ? passwordEncoder.encode(newPassword) : adminUser.passwordHash();
			adminUserStore.save(new AdminUser(trimmedUsername, passwordHash, adminUser.mustChangePassword()));
		} else {
			try {
				userStore.rename(username, trimmedUsername);
			} catch (IllegalStateException e) {
				// Lost a race with a concurrent add/rename onto the same username --
				// the usernameTaken() pre-check above already caught the common case.
				model.addAttribute("errors", List.of("That username is already in use."));
				return "edit-user";
			}
			if (changingPassword) {
				userStore.updatePassword(trimmedUsername, passwordEncoder.encode(newPassword));
			}
		}
		return "redirect:/users" + returnToQuery(returnTo);
	}

	@PostMapping("/users/{username}/delete")
	public String deleteUser(@PathVariable String username,
			@RequestParam(value = "return_to", required = false) String returnTo,
			HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!requireAdmin(request, response)) {
			return null;
		}
		if (username.equals(adminUserStore.load().username())) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "The admin user cannot be removed.");
			return null;
		}
		userStore.remove(username);
		return "redirect:/users" + returnToQuery(returnTo);
	}

	private static boolean containsWhitespace(String username) {
		return username.chars().anyMatch(Character::isWhitespace);
	}

	/** True if username is already the admin's, or already taken in UserStore by someone other than excludingUsername. */
	private boolean usernameTaken(String username, String excludingUsername) {
		if (username.equals(adminUserStore.load().username())) {
			return true;
		}
		return userStore.find(username).isPresent() && !username.equals(excludingUsername);
	}

	private static String returnToQuery(String returnTo) {
		return (returnTo != null && !returnTo.isBlank())
				? "?return_to=" + java.net.URLEncoder.encode(returnTo, java.nio.charset.StandardCharsets.UTF_8)
				: "";
	}

	private boolean requireAdmin(HttpServletRequest request, HttpServletResponse response) throws IOException {
		Optional<IdpSessionService.SessionInfo> session = idpSessionService.validate(request);
		if (session.isEmpty() || !session.get().isAdmin()) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "Admin session required.");
			return false;
		}
		return true;
	}
}
