package online.selfieproxy.identityprovider.internalapi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import online.selfieproxy.identityprovider.domain.AdminUser;
import online.selfieproxy.identityprovider.domain.AdminUserStore;
import online.selfieproxy.identityprovider.domain.PasswordPolicy;
import online.selfieproxy.identityprovider.domain.User;
import online.selfieproxy.identityprovider.domain.UserStore;
import online.selfieproxy.identityprovider.internalapi.dto.ApiErrorDto;
import online.selfieproxy.identityprovider.internalapi.dto.CreateUserRequestDto;
import online.selfieproxy.identityprovider.internalapi.dto.UpdateUserRequestDto;
import online.selfieproxy.identityprovider.internalapi.dto.UserResultDto;
import online.selfieproxy.identityprovider.internalapi.dto.UserSummaryDto;

/**
 * Backend for selfieproxy-portal's Users UI -- data/validation stay here
 * (UserStore/AdminUserStore/PasswordPolicy never leave this app), only the
 * front-end moved to the portal. Reachable only over the second, unpublished
 * Tomcat connector InternalApiFilterConfig/TomcatInternalConnectorConfig set
 * up (see internal-api.port) -- never routed to from the public internet.
 *
 * Every handler here is a direct port of what UsersController used to do in
 * this same app; the only thing that changed is the transport (JSON in/out
 * instead of Model/Thymeleaf) and that requireAdmin()'s IdpSessionService
 * check is gone -- callers of this API are authenticated at the network/token
 * level (InternalApiFilterConfig), not per-request via a browser session.
 */
@RestController
public class InternalUsersController {

	private final UserStore userStore;
	private final AdminUserStore adminUserStore;
	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicy passwordPolicy;

	public InternalUsersController(UserStore userStore, AdminUserStore adminUserStore, PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy) {
		this.userStore = userStore;
		this.adminUserStore = adminUserStore;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
	}

	@GetMapping("/internal/users")
	public List<UserSummaryDto> list() {
		String adminUsername = adminUserStore.load().username();
		List<UserSummaryDto> result = new ArrayList<>();
		result.add(new UserSummaryDto(adminUsername, true));
		userStore.list().stream()
				.sorted(Comparator.comparing(User::username))
				.forEach(u -> result.add(new UserSummaryDto(u.username(), false)));
		return result;
	}

	@GetMapping("/internal/users/{username}")
	public ResponseEntity<?> get(@PathVariable String username) {
		boolean isAdminRow = username.equals(adminUserStore.load().username());
		if (!isAdminRow && userStore.find(username).isEmpty()) {
			return notFound();
		}
		return ResponseEntity.ok(new UserSummaryDto(username, isAdminRow));
	}

	@PostMapping("/internal/users")
	public UserResultDto create(@RequestBody CreateUserRequestDto request) {
		String trimmedUsername = request.username() == null ? "" : request.username().trim();
		if (trimmedUsername.isEmpty()) {
			return UserResultDto.failure(List.of("Username must not be blank."));
		}
		if (containsWhitespace(trimmedUsername)) {
			return UserResultDto.failure(List.of("Username must not contain spaces."));
		}
		if (usernameTaken(trimmedUsername, null)) {
			return UserResultDto.failure(List.of("That username is already in use."));
		}

		List<String> errors = passwordPolicy.validate(request.password(), request.confirmPassword(), (String) null);
		if (!errors.isEmpty()) {
			return UserResultDto.failure(errors);
		}

		try {
			userStore.add(new User(trimmedUsername, passwordEncoder.encode(request.password())));
		} catch (IllegalStateException e) {
			// Lost a race with a concurrent add of the same username -- the
			// usernameTaken() pre-check above already caught the common case.
			return UserResultDto.failure(List.of("That username is already in use."));
		}
		return UserResultDto.ok();
	}

	@PutMapping("/internal/users/{username}")
	public ResponseEntity<?> update(@PathVariable String username, @RequestBody UpdateUserRequestDto request) {
		boolean isAdminRow = username.equals(adminUserStore.load().username());
		if (!isAdminRow && userStore.find(username).isEmpty()) {
			return notFound();
		}

		String trimmedUsername = request.newUsername() == null ? "" : request.newUsername().trim();
		if (trimmedUsername.isEmpty()) {
			return ResponseEntity.ok(UserResultDto.failure(List.of("Username must not be blank.")));
		}
		if (containsWhitespace(trimmedUsername)) {
			return ResponseEntity.ok(UserResultDto.failure(List.of("Username must not contain spaces.")));
		}
		if (!trimmedUsername.equals(username) && usernameTaken(trimmedUsername, isAdminRow ? null : username)) {
			return ResponseEntity.ok(UserResultDto.failure(List.of("That username is already in use.")));
		}

		String newPassword = request.newPassword() == null ? "" : request.newPassword();
		String confirmNewPassword = request.confirmNewPassword() == null ? "" : request.confirmNewPassword();
		String currentPassword = request.currentPassword() == null ? "" : request.currentPassword();

		// Blank new-password fields mean "leave the password as-is" -- the
		// password section on the edit page is optional, unlike the required
		// fields used when adding a brand-new user.
		boolean changingPassword = !newPassword.isEmpty() || !confirmNewPassword.isEmpty();
		AdminUser adminUser = isAdminRow ? adminUserStore.load() : null;
		if (changingPassword) {
			if (isAdminRow) {
				if (!passwordEncoder.matches(currentPassword, adminUser.passwordHash())) {
					return ResponseEntity.ok(UserResultDto.failure(List.of("Current password is incorrect.")));
				}
				List<String> errors = passwordPolicy.validate(newPassword, confirmNewPassword, adminUser);
				if (!errors.isEmpty()) {
					return ResponseEntity.ok(UserResultDto.failure(errors));
				}
			} else {
				User user = userStore.find(username).orElseThrow();
				List<String> errors = passwordPolicy.validate(newPassword, confirmNewPassword, user.passwordHash());
				if (!errors.isEmpty()) {
					return ResponseEntity.ok(UserResultDto.failure(errors));
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
				return ResponseEntity.ok(UserResultDto.failure(List.of("That username is already in use.")));
			}
			if (changingPassword) {
				userStore.updatePassword(trimmedUsername, passwordEncoder.encode(newPassword));
			}
		}
		return ResponseEntity.ok(UserResultDto.ok());
	}

	@DeleteMapping("/internal/users/{username}")
	public ResponseEntity<?> delete(@PathVariable String username) {
		if (username.equals(adminUserStore.load().username())) {
			return ResponseEntity.badRequest().body(new ApiErrorDto("The admin user cannot be removed."));
		}
		userStore.remove(username);
		return ResponseEntity.noContent().build();
	}

	private static boolean containsWhitespace(String username) {
		return username.chars().anyMatch(Character::isWhitespace);
	}

	/** True if username is already the admin's, or already taken in UserStore by someone other than excludingUsername. */
	private boolean usernameTaken(String username, String excludingUsername) {
		if (username.equals(adminUserStore.load().username())) {
			return true;
		}
		Optional<User> existing = userStore.find(username);
		return existing.isPresent() && !username.equals(excludingUsername);
	}

	private static ResponseEntity<ApiErrorDto> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorDto("Unknown user."));
	}
}
