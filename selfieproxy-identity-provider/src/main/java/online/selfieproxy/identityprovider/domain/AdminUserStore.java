package online.selfieproxy.identityprovider.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import online.selfieproxy.identityprovider.config.AdminAuthProperties;
import online.selfieproxy.identityprovider.config.OidcProperties;

/**
 * Self-provisions Selfie Proxy's persisted admin-password record, mirroring
 * SigningKeyProvider's check-disk/generate-if-absent/persist shape: on first
 * boot, if no record file exists yet, bcrypt-hashes
 * admin-portal.bootstrap-password into a new record with
 * mustChangePassword=true. Every subsequent login/change goes through this
 * file, so ADMIN_PORTAL_BOOTSTRAP_PASSWORD in .env is only ever consulted
 * once. A read/parse failure of an *existing* record file is treated as real
 * corruption and is fatal, same posture as SigningKeyProvider.
 *
 * Skipped entirely when OidcProperties.isExternal() -- an operator running
 * with an external IdP never authenticates against this bundled server, so
 * there's no reason to hash and persist a bootstrap password (least of all
 * one left blank, which would otherwise seed a live admin account with an
 * empty password) for a record LoginController/UsersController will never
 * legitimately be asked to check.
 *
 * load() also migrates records persisted before the username field existed:
 * such a file deserializes with username()==null (Jackson leaves missing
 * record components null rather than failing), which would otherwise both
 * blank out the Users page's admin-row username field and permanently break
 * LoginController's username check ("admin".equals(null) is always false).
 * Backfilled from admin-portal.username and persisted immediately so this
 * only happens once.
 */
@Component
public class AdminUserStore {

	private static final Logger log = LoggerFactory.getLogger(AdminUserStore.class);

	private final Path path;
	private final AdminAuthProperties authProperties;
	private final PasswordEncoder passwordEncoder;
	private final OidcProperties oidcProperties;
	private final ObjectMapper objectMapper = JsonMapper.builder().build();
	private final Object lock = new Object();

	public AdminUserStore(@Value("${admin-portal.user-store-path}") String path, AdminAuthProperties authProperties,
			PasswordEncoder passwordEncoder, OidcProperties oidcProperties) {
		this.path = Path.of(path);
		this.authProperties = authProperties;
		this.passwordEncoder = passwordEncoder;
		this.oidcProperties = oidcProperties;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void bootstrapIfAbsent() {
		if (Files.exists(path)) {
			return;
		}
		if (oidcProperties.isExternal()) {
			log.info("Skipping admin password bootstrap -- OIDC_ISSUER_URL is set, an external IdP is in use.");
			return;
		}
		try {
			Files.createDirectories(path.getParent());
		} catch (IOException e) {
			throw new IllegalStateException("Failed to create parent directory for " + path, e);
		}
		save(new AdminUser(authProperties.username(), passwordEncoder.encode(authProperties.bootstrapPassword()), true));
		log.info("Bootstrapped admin password record at {}", path);
	}

	public AdminUser load() {
		synchronized (lock) {
			AdminUser user;
			try {
				user = objectMapper.readValue(path.toFile(), AdminUser.class);
			} catch (Exception e) {
				throw new IllegalStateException("Failed to read " + path, e);
			}
			if (user.username() == null || user.username().isBlank()) {
				user = new AdminUser(authProperties.username(), user.passwordHash(), user.mustChangePassword());
				save(user);
				log.info("Migrated admin record at {} to include a username (backfilled from admin-portal.username)", path);
			}
			return user;
		}
	}

	public void save(AdminUser user) {
		synchronized (lock) {
			try {
				objectMapper.writeValue(path.toFile(), user);
			} catch (Exception e) {
				throw new IllegalStateException("Failed to write " + path, e);
			}
		}
	}
}
