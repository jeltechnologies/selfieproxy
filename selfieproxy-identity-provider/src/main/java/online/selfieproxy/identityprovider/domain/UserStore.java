package online.selfieproxy.identityprovider.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persisted list of non-admin Users -- login-only identities that can
 * authenticate against any exposed app protected with single sign on but never the portal
 * (see User, and the is_admin claim gate in selfieproxy-reverseproxy's
 * oidc_auth.go). Mirrors AdminUserStore's persist-to-JSON/lock pattern, but
 * over a list instead of a singleton, and with no bootstrap event: the file
 * simply doesn't exist until the first user is added, so a missing file
 * means "no users yet", not corruption -- unlike AdminUserStore, whose
 * existence is guaranteed by its own bootstrap.
 *
 * Username uniqueness against the admin's own username (Gap B) is a
 * cross-store concern InternalUsersController checks before calling add()/rename(),
 * since this store has no visibility into AdminUserStore. Uniqueness
 * *within this store*, though, is enforced right here, inside the same
 * synchronized read-modify-write add()/rename() already does -- a
 * check performed by the caller first (as InternalUsersController's own
 * pre-check does, for a fast, friendly error) is a separate, non-atomic
 * read, and two concurrent requests for the same username (a double
 * form submit, two open tabs) could both pass it and both write,
 * leaving two User records with the identical username. add()/rename()
 * throw IllegalStateException on a same-store collision so that can't
 * happen even under a race.
 */
@Component
public class UserStore {

	private final Path path;
	private final ObjectMapper objectMapper = JsonMapper.builder().build();
	private final Object lock = new Object();

	public UserStore(@Value("${users.store-path}") String path) {
		this.path = Path.of(path);
	}

	public List<User> list() {
		synchronized (lock) {
			return readAll();
		}
	}

	public Optional<User> find(String username) {
		synchronized (lock) {
			return readAll().stream().filter(u -> u.username().equals(username)).findFirst();
		}
	}

	/** @throws IllegalStateException if a user with this username already exists. */
	public void add(User user) {
		synchronized (lock) {
			List<User> all = readAll();
			if (all.stream().anyMatch(u -> u.username().equals(user.username()))) {
				throw new IllegalStateException("Username already exists: " + user.username());
			}
			all.add(user);
			writeAll(all);
		}
	}

	/** @throws IllegalStateException if newUsername is already used by a different user. */
	public void rename(String oldUsername, String newUsername) {
		synchronized (lock) {
			List<User> all = readAll();
			boolean collides = all.stream()
					.anyMatch(u -> !u.username().equals(oldUsername) && u.username().equals(newUsername));
			if (collides) {
				throw new IllegalStateException("Username already exists: " + newUsername);
			}
			List<User> updated = all.stream()
					.map(u -> u.username().equals(oldUsername) ? new User(newUsername, u.passwordHash()) : u)
					.toList();
			writeAll(updated);
		}
	}

	public void updatePassword(String username, String newPasswordHash) {
		synchronized (lock) {
			List<User> all = readAll();
			List<User> updated = all.stream()
					.map(u -> u.username().equals(username) ? new User(u.username(), newPasswordHash) : u)
					.toList();
			writeAll(updated);
		}
	}

	public void remove(String username) {
		synchronized (lock) {
			List<User> all = readAll();
			all.removeIf(u -> u.username().equals(username));
			writeAll(all);
		}
	}

	private List<User> readAll() {
		if (!Files.exists(path)) {
			return new ArrayList<>();
		}
		JavaType listType = objectMapper.getTypeFactory().constructCollectionType(ArrayList.class, User.class);
		try {
			return objectMapper.readValue(path.toFile(), listType);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to read " + path, e);
		}
	}

	private void writeAll(List<User> all) {
		try {
			Files.createDirectories(path.getParent());
			objectMapper.writeValue(path.toFile(), all);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to write " + path, e);
		}
	}
}
