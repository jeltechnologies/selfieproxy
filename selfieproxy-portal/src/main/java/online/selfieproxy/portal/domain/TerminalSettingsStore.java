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
import tools.jackson.databind.json.JsonMapper;

/**
 * Read/write mirror of selfieproxy-remote-console's own TerminalSettingsStore, pointed at the
 * same file over the shared /data volume (same property name/default path). Used only by
 * BackupService: load() to include the SSH console settings in a configuration export, save() to
 * apply them on restore -- this module is otherwise never a writer of this file (remote-console's
 * own Settings panel owns it day-to-day), the same narrow extension of the shared-JSON-file
 * convention ThemeStore already established for the UI theme.
 *
 * A missing or corrupt file is never fatal -- load() falls back to the same defaults
 * selfieproxy-remote-console uses (fontSize 15, theme "dark", font family "default").
 */
@Component
public class TerminalSettingsStore {

	private static final Logger log = LoggerFactory.getLogger(TerminalSettingsStore.class);

	private static final int DEFAULT_FONT_SIZE = 15;
	private static final String DEFAULT_THEME_ID = "dark";
	private static final String DEFAULT_FONT_FAMILY_ID = "default";

	private final Path filePath;
	private final ObjectMapper objectMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();
	private final Object lock = new Object();

	public TerminalSettingsStore(@Value("${selfieproxy.terminal-settings-path}") String path) {
		this.filePath = Path.of(path);
	}

	public TerminalSettings load() {
		synchronized (lock) {
			if (!Files.exists(filePath)) {
				return defaults();
			}
			try {
				return objectMapper.readValue(filePath.toFile(), TerminalSettings.class);
			} catch (Exception e) {
				log.warn("Failed to read {}, falling back to defaults", filePath, e);
				return defaults();
			}
		}
	}

	public void save(TerminalSettings settings) {
		synchronized (lock) {
			try {
				Files.createDirectories(filePath.getParent());
				objectMapper.writeValue(filePath.toFile(), settings);
			} catch (IOException e) {
				throw new IllegalStateException("Failed to write " + filePath, e);
			}
		}
	}

	private TerminalSettings defaults() {
		return new TerminalSettings(DEFAULT_FONT_SIZE, DEFAULT_THEME_ID, DEFAULT_FONT_FAMILY_ID);
	}
}
