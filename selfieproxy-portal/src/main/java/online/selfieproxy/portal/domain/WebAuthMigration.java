package online.selfieproxy.portal.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One-way, idempotent migration of the {@code web} node in servers.json (and in a configuration
 * export's manifest.json) from the original {@code ssoProtected} boolean to {@link WebAuthMethod}.
 *
 * <p>This has to run against the raw tree <em>before</em> Jackson binds it, not as a fixup
 * afterwards: ServerStore's and BackupService's mappers both leave FAIL_ON_UNKNOWN_PROPERTIES
 * enabled, so the moment {@code WebConfig} stops having an {@code ssoProtected} component, every
 * pre-existing servers.json and every previously-taken export becomes unreadable -- and in
 * ServerStore's case that failure is fatal on every path, including the ones that would otherwise
 * have rewritten the file.
 *
 * <p>The mapping preserves behaviour exactly rather than applying the new-Server default:
 * {@code true} was a login redirect and stays one, {@code false} was open to the internet and
 * stays open. Silently protecting servers that were deliberately left open would break every
 * non-browser client pointed at them (see selfieproxy-portal/CLAUDE.md's Servers section).
 *
 * <p>Idempotent by inspection rather than by marker file, unlike AgentBootstrap and
 * LocalWebsiteDemoBootstrap: an entry that already has {@code authMethod} is left alone, so a
 * migrated file is a no-op on every subsequent startup and a file containing a mix (a hand-edited
 * one, or a partial restore) converges correctly.
 */
final class WebAuthMigration {

	private WebAuthMigration() {
	}

	/**
	 * Rewrites every legacy {@code web} node under {@code servers}, which may be either the
	 * fqdn-keyed object servers.json stores or the array a manifest holds.
	 *
	 * @return true if anything was changed, and the caller therefore needs to persist the tree
	 */
	static boolean migrateServers(JsonNode servers) {
		if (servers == null || servers.isNull()) {
			return false;
		}
		boolean changed = false;
		for (JsonNode server : servers) {
			changed |= migrateServer(server);
		}
		return changed;
	}

	private static boolean migrateServer(JsonNode server) {
		if (server == null || !server.isObject()) {
			return false;
		}
		JsonNode web = server.get("web");
		if (web == null || !web.isObject()) {
			return false;
		}
		ObjectNode webObject = (ObjectNode) web;
		if (webObject.has("authMethod")) {
			return false;
		}
		JsonNode ssoProtected = webObject.get("ssoProtected");
		// A web node with neither property is a shape this code has never written; default it to
		// the safe-for-the-admin reading -- unchanged behaviour is "whatever it was doing", and
		// with no ssoProtected flag present that was an open tunnel.
		boolean sso = ssoProtected != null && ssoProtected.asBoolean(false);
		webObject.put("authMethod", (sso ? WebAuthMethod.SSO : WebAuthMethod.NONE).name());
		webObject.remove("ssoProtected");
		return true;
	}
}
