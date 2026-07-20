package online.selfieproxy.portal.web;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletResponse;

import online.selfieproxy.portal.identityprovider.IdentityProviderClient;
import online.selfieproxy.portal.identityprovider.dto.CreateUserRequestDto;
import online.selfieproxy.portal.identityprovider.dto.UpdateUserRequestDto;
import online.selfieproxy.portal.identityprovider.dto.UserResultDto;
import online.selfieproxy.portal.identityprovider.dto.UserSummaryDto;

/**
 * Admin-only management of non-admin Users, sharing the portal's own
 * look/topbar (see fragments/layout.html) -- data and validation stay in
 * selfieproxy-identity-provider (UserStore/AdminUserStore/PasswordPolicy),
 * reached here only through IdentityProviderClient. The portal's already-registered
 * SessionInterceptor gates every route below on a verified admin session, the
 * same as every other portal page -- no separate admin check needed here,
 * unlike identity-provider's old UsersController which had to hand-roll one.
 */
@Controller
public class UsersController {

	private final IdentityProviderClient identityProviderClient;

	public UsersController(IdentityProviderClient identityProviderClient) {
		this.identityProviderClient = identityProviderClient;
	}

	@GetMapping("/users")
	public String list(Model model) {
		List<UserSummaryDto> all = identityProviderClient.listUsers();
		String adminUsername = all.stream()
				.filter(UserSummaryDto::isAdmin)
				.map(UserSummaryDto::username)
				.findFirst()
				.orElse("");
		List<String> regularUsernames = all.stream()
				.filter(u -> !u.isAdmin())
				.map(UserSummaryDto::username)
				.sorted()
				.toList();

		model.addAttribute("adminUsername", adminUsername);
		model.addAttribute("users", regularUsernames);
		return "users";
	}

	@GetMapping("/users/new")
	public String newUserPage(Model model) {
		model.addAttribute("isNew", true);
		model.addAttribute("isAdminRow", false);
		model.addAttribute("username", "");
		return "edit-user";
	}

	@PostMapping("/users/new")
	public String createUser(@RequestParam String username, @RequestParam String password,
			@RequestParam String confirmPassword, Model model) {
		model.addAttribute("isNew", true);
		model.addAttribute("isAdminRow", false);
		model.addAttribute("username", username);

		UserResultDto result = identityProviderClient
				.createUser(new CreateUserRequestDto(username, password, confirmPassword));
		if (!result.success()) {
			model.addAttribute("errors", result.errors());
			return "edit-user";
		}
		return "redirect:/users";
	}

	@GetMapping("/users/{username}/edit")
	public String editUserPage(@PathVariable String username, Model model, HttpServletResponse response)
			throws IOException {
		Optional<UserSummaryDto> user = identityProviderClient.findUser(username);
		if (user.isEmpty()) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown user.");
			return null;
		}
		model.addAttribute("isNew", false);
		model.addAttribute("isAdminRow", user.get().isAdmin());
		model.addAttribute("username", username);
		return "edit-user";
	}

	@PostMapping("/users/{username}/edit")
	public String updateUser(@PathVariable String username, @RequestParam("username") String newUsername,
			@RequestParam(required = false, defaultValue = "") String currentPassword,
			@RequestParam(required = false, defaultValue = "") String newPassword,
			@RequestParam(required = false, defaultValue = "") String confirmNewPassword, Model model,
			HttpServletResponse response) throws IOException {
		Optional<UserSummaryDto> user = identityProviderClient.findUser(username);
		if (user.isEmpty()) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown user.");
			return null;
		}

		model.addAttribute("isNew", false);
		model.addAttribute("isAdminRow", user.get().isAdmin());
		model.addAttribute("username", newUsername.trim());

		UserResultDto result = identityProviderClient.updateUser(username,
				new UpdateUserRequestDto(newUsername, currentPassword, newPassword, confirmNewPassword));
		if (!result.success()) {
			model.addAttribute("errors", result.errors());
			return "edit-user";
		}
		return "redirect:/users";
	}

	@PostMapping("/users/{username}/delete")
	public String deleteUser(@PathVariable String username) {
		identityProviderClient.deleteUser(username);
		return "redirect:/users";
	}
}
