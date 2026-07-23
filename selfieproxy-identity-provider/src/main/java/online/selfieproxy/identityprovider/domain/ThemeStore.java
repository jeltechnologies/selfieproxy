package online.selfieproxy.identityprovider.domain;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only mirror of theme.json (selfieproxy-portal owns writing it -- see that module's own
 * Theme/ThemeStore/AppearanceController), read over the shared /data volume via the same
 * selfieproxy.theme-path property/default path. Same read-only-mirror shape as
 * selfieproxy-remote-console's RemoteConsoleStore reading selfieproxy-portal's exposed-apps.json.
 *
 * A missing or corrupt file is never fatal -- this setting backs the pre-auth login page, so it
 * must never be capable of blocking a login. load() falls back to Theme.LIGHT and logs a warning
 * instead of throwing.
 */
@Component
public class ThemeStore {

	private static final Logger log = LoggerFactory.getLogger(ThemeStore.class);

	private final Path filePath;
	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	public ThemeStore(@Value("${selfieproxy.theme-path}") String path) {
		this.filePath = Path.of(path);
	}

	public Theme load() {
		if (!Files.exists(filePath)) {
			return Theme.LIGHT;
		}
		try {
			ThemeSetting setting = objectMapper.readValue(filePath.toFile(), ThemeSetting.class);
			return Theme.fromId(setting.themeId());
		} catch (Exception e) {
			log.warn("Failed to read {}, falling back to Theme.LIGHT", filePath, e);
			return Theme.LIGHT;
		}
	}
}
