package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selfie Proxy's single shared UI theme setting (Light/Dark/Dracula) -- the same mode is also used
 * by selfieproxy-identity-provider's login/change-password/logged-out pages, over the shared /data
 * volume (see that module's own read-only ThemeStore, the same mirror shape as
 * selfieproxy-remote-console's RemoteConsoleStore reading exposed-apps.json). Written only here,
 * from AppearanceController.
 *
 * Unlike DomainStore/AdminUserStore, a missing or corrupt theme.json is never fatal -- this is a
 * cosmetic setting, not a credential, and must never block a page (least of all identity-provider's
 * login page) from rendering. load() falls back to Theme.LIGHT and logs a warning instead of
 * throwing.
 */
@Component
public class ThemeStore {

	private static final Logger log = LoggerFactory.getLogger(ThemeStore.class);

	private final Path filePath;
	private final ObjectMapper objectMapper = JsonMapper.builder().build();
	private final Object lock = new Object();

	public ThemeStore(@Value("${selfieproxy.theme-path}") String path) {
		this.filePath = Path.of(path);
	}

	public Theme load() {
		synchronized (lock) {
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

	public void save(Theme theme) {
		synchronized (lock) {
			try {
				Files.createDirectories(filePath.getParent());
				objectMapper.writeValue(filePath.toFile(), new ThemeSetting(theme.id()));
			} catch (IOException e) {
				throw new IllegalStateException("Failed to write " + filePath, e);
			}
		}
	}
}
