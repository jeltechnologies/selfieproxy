package online.selfieproxy.remoteconsole.domain;

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
 * Persists the SSH console's font size/font family/color theme (see settings.js's Settings panel
 * -- this is the server-side counterpart, replacing the old browser-only localStorage). Also read
 * and written by selfieproxy-portal's own mirrored TerminalSettingsStore for configuration
 * export/import, over the shared /data volume -- same idiom as selfieproxy-portal's ThemeStore.
 *
 * A missing or corrupt file is never fatal -- load() falls back to the same defaults settings.js
 * used to hardcode (fontSize 15, theme "dark", font family "default") and logs a warning instead
 * of throwing, since a broken settings file must never block a console session from opening.
 */
@Component
public class TerminalSettingsStore {

	private static final Logger log = LoggerFactory.getLogger(TerminalSettingsStore.class);

	private static final int DEFAULT_FONT_SIZE = 15;
	private static final String DEFAULT_THEME_ID = "dark";
	private static final String DEFAULT_FONT_FAMILY_ID = "default";
	private static final int MIN_FONT_SIZE = 10;
	private static final int MAX_FONT_SIZE = 24;

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
			TerminalSettings clamped = new TerminalSettings(
					Math.min(MAX_FONT_SIZE, Math.max(MIN_FONT_SIZE, settings.fontSize())),
					settings.themeId(), settings.fontFamilyId());
			try {
				Files.createDirectories(filePath.getParent());
				objectMapper.writeValue(filePath.toFile(), clamped);
			} catch (IOException e) {
				throw new IllegalStateException("Failed to write " + filePath, e);
			}
		}
	}

	private TerminalSettings defaults() {
		return new TerminalSettings(DEFAULT_FONT_SIZE, DEFAULT_THEME_ID, DEFAULT_FONT_FAMILY_ID);
	}
}
