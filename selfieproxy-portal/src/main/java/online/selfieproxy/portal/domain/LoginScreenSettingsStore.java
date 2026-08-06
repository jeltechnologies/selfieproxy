package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists LoginScreenSettings to data/selfieproxy/login-screen-settings.json. Also read by
 * selfieproxy-identity-provider's own mirrored, read-only LoginScreenSettingsStore over the same
 * shared /data volume -- same idiom as ThemeStore/TerminalSettingsStore.
 *
 * A missing or corrupt file is never fatal -- load() falls back to LoginScreenSettings.defaults()
 * and logs a warning instead of throwing, since a broken settings file must never block the
 * identity-provider's login page from rendering.
 */
@Component
public class LoginScreenSettingsStore {

	private static final Logger log = LoggerFactory.getLogger(LoginScreenSettingsStore.class);

	private final Path filePath;
	private final ObjectMapper objectMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(SerializationFeature.INDENT_OUTPUT)
			.build();
	private final Object lock = new Object();

	public LoginScreenSettingsStore(@Value("${selfieproxy.login-screen-settings-path}") String path) {
		this.filePath = Path.of(path);
	}

	public LoginScreenSettings load() {
		synchronized (lock) {
			if (!Files.exists(filePath)) {
				return LoginScreenSettings.defaults();
			}
			try {
				return normalize(objectMapper.readValue(filePath.toFile(), LoginScreenSettings.class));
			} catch (Exception e) {
				log.warn("Failed to read {}, falling back to defaults", filePath, e);
				return LoginScreenSettings.defaults();
			}
		}
	}

	public void save(LoginScreenSettings settings) {
		synchronized (lock) {
			try {
				Files.createDirectories(filePath.getParent());
				objectMapper.writeValue(filePath.toFile(), settings);
			} catch (IOException e) {
				throw new IllegalStateException("Failed to write " + filePath, e);
			}
		}
	}

	/** Backfills any text field missing from a settings file saved before that field existed (e.g. loginButtonLabel) with its default, so a field added later never renders blank on the login page. */
	private static LoginScreenSettings normalize(LoginScreenSettings settings) {
		return new LoginScreenSettings(
				settings.logoEnabled(),
				blankToDefault(settings.title(), LoginScreenSettings.DEFAULT_TITLE),
				blankToDefault(settings.slogan(), LoginScreenSettings.DEFAULT_SLOGAN),
				blankToDefault(settings.usernameLabel(), LoginScreenSettings.DEFAULT_USERNAME_LABEL),
				blankToDefault(settings.passwordLabel(), LoginScreenSettings.DEFAULT_PASSWORD_LABEL),
				blankToDefault(settings.loginButtonLabel(), LoginScreenSettings.DEFAULT_LOGIN_BUTTON_LABEL),
				settings.footerEnabled(),
				settings.backgroundLightFilename(),
				settings.backgroundDarkFilename());
	}

	private static String blankToDefault(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value;
	}
}
