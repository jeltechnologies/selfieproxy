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
 * Remembers the "Filter by domain" dropdown's selected value on the Applications and Local
 * Websites list pages, so it survives page navigation and a server reboot -- same self-contained
 * single-value JSON store shape as ThemeStore/LastUsedAppDefaultsStore. A missing or corrupt file
 * is never fatal (load() falls back to "no filter selected" on both fields), since this is a
 * cosmetic UI preference, not load-bearing state.
 */
@Component
public class DomainFilterPreferenceStore {

	private static final Logger log = LoggerFactory.getLogger(DomainFilterPreferenceStore.class);

	private final Path filePath;
	private final ObjectMapper objectMapper = JsonMapper.builder().build();
	private final Object lock = new Object();

	public DomainFilterPreferenceStore(@Value("${selfieproxy.domain-filter-preference-path}") String path) {
		this.filePath = Path.of(path);
	}

	public DomainFilterPreference load() {
		synchronized (lock) {
			if (!Files.exists(filePath)) {
				return new DomainFilterPreference(null, null);
			}
			try {
				return objectMapper.readValue(filePath.toFile(), DomainFilterPreference.class);
			} catch (Exception e) {
				log.warn("Failed to read {}, falling back to no remembered domain filter", filePath, e);
				return new DomainFilterPreference(null, null);
			}
		}
	}

	public void saveAppsDomain(String domain) {
		synchronized (lock) {
			DomainFilterPreference current = load();
			save(new DomainFilterPreference(domain, current.localWebsitesDomain()));
		}
	}

	public void saveLocalWebsitesDomain(String domain) {
		synchronized (lock) {
			DomainFilterPreference current = load();
			save(new DomainFilterPreference(current.appsDomain(), domain));
		}
	}

	private void save(DomainFilterPreference preference) {
		try {
			Files.createDirectories(filePath.getParent());
			objectMapper.writeValue(filePath.toFile(), preference);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to write " + filePath, e);
		}
	}
}
