package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selfie Proxy's own complete record of every ExposedApp, keyed by its full
 * FQDN (subdomain + domain -- see ExposedApp.fqdn()), held alongside (not
 * instead of) BoringProxy's own Tunnel database. Keying by FQDN rather than
 * bare subdomain means two apps can share a label under different domains
 * without colliding, and the key is literally identical to BoringProxy's own
 * Tunnel.domain. Two
 * purposes: (1) BoringProxy's Tunnel schema can't reliably represent some
 * fields we care about -- eg. the homelab's protocol is only
 * recoverable by convention (an "https://" prefix on client_address) that
 * BoringProxy's own legacy web UI doesn't follow -- and (2) this is meant to
 * become a complete, BoringProxy-independent description of every exposed
 * app, so that BoringProxy itself can eventually be swapped out (eg. for
 * Traefik) without losing app configuration. {@link #reconcile} both reads
 * (overlaying our authoritative fields onto BoringProxy's live data) and
 * writes (capturing a full snapshot the first time we observe an app we
 * don't have a record for yet) towards that end.
 */
@Component
public class ExposedAppStore {

	private final Path filePath;
	// New boolean fields (eg. managedStaticSite) are absent -- not
	// merely null -- in exposed-apps.json entries written before they existed;
	// without this, Jackson's default record deserialization treats an absent
	// primitive-boolean property as an explicit null and throws instead of
	// defaulting to false, breaking every schema addition since the store's
	// whole point is to evolve over time (see the class javadoc).
	private final ObjectMapper objectMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.build();
	private final Object lock = new Object();

	public ExposedAppStore(@Value("${selfieproxy.exposed-apps-path}") String path) {
		this.filePath = Path.of(path);
	}

	public void save(ExposedApp app) {
		synchronized (lock) {
			Map<String, ExposedApp> all = readAll();
			all.put(app.fqdn(), app);
			writeAll(all);
		}
	}

	public void delete(String fqdn) {
		synchronized (lock) {
			Map<String, ExposedApp> all = readAll();
			if (all.remove(fqdn) != null) {
				writeAll(all);
			}
		}
	}

	/**
	 * If we have no record for fromBoringProxy's FQDN yet, persists it
	 * as-is (a best-effort full snapshot, capturing apps that only ever
	 * existed via the legacy BoringProxy UI) and returns it unchanged.
	 * Otherwise leaves the existing record untouched and overlays our own
	 * protocol/tlsMode -- fields BoringProxy can't reliably represent --
	 * onto BoringProxy's live connectivity data (host/port/etc.), so an
	 * explicit edit made through Selfie Proxy is never clobbered by a
	 * subsequent auto-capture.
	 */
	public ExposedApp reconcile(ExposedApp fromBoringProxy) {
		synchronized (lock) {
			Map<String, ExposedApp> all = readAll();
			ExposedApp stored = all.get(fromBoringProxy.fqdn());
			if (stored == null) {
				all.put(fromBoringProxy.fqdn(), fromBoringProxy);
				writeAll(all);
				return fromBoringProxy;
			}
			return new ExposedApp(fromBoringProxy.subdomain(), stored.name(),
					fromBoringProxy.homelabName(), fromBoringProxy.type(), stored.protocol(), fromBoringProxy.host(),
					fromBoringProxy.port(), fromBoringProxy.exposedPort(), stored.tlsMode(), stored.ssoProtected(),
					fromBoringProxy.domain(), stored.mode(), stored.username(),
					stored.encryptedSecret(), stored.ignoreCertificate());
		}
	}

	/** The stored record for fqdn, or null if none exists yet (eg. a tunnel that's never been through reconcile/save). */
	public ExposedApp find(String fqdn) {
		synchronized (lock) {
			return readAll().get(fqdn);
		}
	}

	private Map<String, ExposedApp> readAll() {
		if (!Files.exists(filePath)) {
			return new LinkedHashMap<>();
		}
		JavaType mapType = objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class,
				ExposedApp.class);
		try {
			return objectMapper.readValue(filePath.toFile(), mapType);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to read " + filePath, e);
		}
	}

	private void writeAll(Map<String, ExposedApp> all) {
		try {
			Files.createDirectories(filePath.getParent());
			objectMapper.writeValue(filePath.toFile(), all);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to write " + filePath, e);
		}
	}
}
