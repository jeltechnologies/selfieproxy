package online.selfieproxy.identityprovider.domain;

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
 * Read-only mirror of login-screen-settings.json (selfieproxy-portal owns writing it -- see that
 * module's own LoginScreenSettings/LoginScreenSettingsStore/LoginScreenController), read over the
 * shared /data volume via the same selfieproxy.login-screen-settings-path property/default path.
 * Same read-only-mirror shape as this module's own ThemeStore.
 *
 * A missing or corrupt file is never fatal -- this setting backs the pre-auth login page, so it
 * must never be capable of blocking a login. load() falls back to LoginScreenSettings.defaults()
 * and logs a warning instead of throwing.
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

	public LoginScreenSettingsStore(@Value("${selfieproxy.login-screen-settings-path}") String path) {
		this.filePath = Path.of(path);
	}

	public LoginScreenSettings load() {
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
