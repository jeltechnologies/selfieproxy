package online.selfieproxy.portal.domain;

import java.util.List;

/**
 * The full contents of a Selfie Proxy backup, serialized as manifest.json at
 * the root of the backup ZIP (see BackupService). Uses the same bespoke,
 * camelCase ObjectMapper convention as ExposedAppStore/LocalWebsiteStore
 * (not the globally-configured snake_case one used for REST DTOs) since this
 * is an internal persistence format, not an API response.
 *
 * @param version        manifest schema version -- BackupService rejects any value it doesn't recognize
 * @param createdAt      ISO-8601 instant (server UTC) the backup was created, informational only
 * @param sourceDomain   the DOMAIN of the server the backup was taken from, informational only -- restore never depends on it, since subdomains are already relative
 * @param homelabs       every Homelab (Agent) name except the hidden "This Server" one -- see ThisServerAgentProperties
 * @param exposedApps    every Exposed App ("server"), the same merged view ExposedAppController itself shows/edits
 * @param localWebsites  every Local Website's config; its content files live in the ZIP under local-websites/&lt;fqdn&gt;/
 */
public record BackupManifest(
		int version,
		String createdAt,
		String sourceDomain,
		List<String> homelabs,
		List<ExposedApp> exposedApps,
		List<LocalWebsite> localWebsites) {

	public static final int CURRENT_VERSION = 1;
}
