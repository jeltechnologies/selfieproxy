package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The migration these cover is what lets an existing deployment survive the upgrade: without it
 * every servers.json written before WebAuthMethod existed becomes unreadable, fatally, on every
 * ServerStore path (see WebAuthMigration's javadoc).
 */
class WebAuthMigrationTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	private JsonNode parse(String json) {
		return mapper.readTree(json);
	}

	@Test
	void ssoProtectedTrueBecomesSso() {
		JsonNode root = parse("""
				{"nas.example.com": {"web": {"protocol": "HTTPS", "port": 443, "ssoProtected": true}}}""");

		assertTrue(WebAuthMigration.migrateServers(root));

		JsonNode web = root.get("nas.example.com").get("web");
		assertEquals("SSO", web.get("authMethod").asString());
		assertNull(web.get("ssoProtected"));
	}

	@Test
	void ssoProtectedFalseBecomesNoneRatherThanTheNewServerDefault() {
		JsonNode root = parse("""
				{"plex.example.com": {"web": {"protocol": "HTTP", "port": 32400, "ssoProtected": false}}}""");

		assertTrue(WebAuthMigration.migrateServers(root));

		JsonNode web = root.get("plex.example.com").get("web");
		// Deliberately NOT SSO: a server the admin left open must keep working for its
		// non-browser clients, which can't follow a login redirect.
		assertEquals("NONE", web.get("authMethod").asString());
	}

	@Test
	void alreadyMigratedIsUntouched() {
		JsonNode root = parse("""
				{"api.example.com": {"web": {"protocol": "HTTPS", "port": 443, "authMethod": "TOKEN",
				 "tokenValue": "cipher"}}}""");

		assertFalse(WebAuthMigration.migrateServers(root));

		JsonNode web = root.get("api.example.com").get("web");
		assertEquals("TOKEN", web.get("authMethod").asString());
		assertEquals("cipher", web.get("tokenValue").asString());
	}

	@Test
	void webNodeWithNeitherPropertyDefaultsToNone() {
		JsonNode root = parse("""
				{"odd.example.com": {"web": {"protocol": "HTTPS", "port": 443}}}""");

		assertTrue(WebAuthMigration.migrateServers(root));
		assertEquals("NONE", root.get("odd.example.com").get("web").get("authMethod").asString());
	}

	@Test
	void serverWithoutWebIsIgnored() {
		JsonNode root = parse("""
				{"ssh.example.com": {"terminal": {"port": 22}}}""");

		assertFalse(WebAuthMigration.migrateServers(root));
	}

	@Test
	void mixedFileConvergesAndIsIdempotent() {
		JsonNode root = parse("""
				{"a.example.com": {"web": {"port": 443, "ssoProtected": true}},
				 "b.example.com": {"web": {"port": 80, "authMethod": "BASIC"}}}""");

		assertTrue(WebAuthMigration.migrateServers(root));
		assertEquals("SSO", root.get("a.example.com").get("web").get("authMethod").asString());
		assertEquals("BASIC", root.get("b.example.com").get("web").get("authMethod").asString());

		// A second startup must be a no-op -- this is what keeps it from writing a fresh .bak
		// on every single boot.
		assertFalse(WebAuthMigration.migrateServers(root));
	}

	@Test
	void manifestArrayOfServersIsMigratedToo() {
		// An export's manifest holds servers as an array, not the fqdn-keyed object servers.json
		// uses -- BackupService.readManifest runs the same transform so older exports still import.
		JsonNode root = parse("""
				[{"web": {"port": 443, "ssoProtected": true}}, {"web": {"port": 80, "ssoProtected": false}}]""");

		assertTrue(WebAuthMigration.migrateServers(root));
		assertEquals("SSO", root.get(0).get("web").get("authMethod").asString());
		assertEquals("NONE", root.get(1).get("web").get("authMethod").asString());
	}

	@Test
	void nullServersNodeIsTolerated() {
		// A manifest with no servers key at all -- root.get("servers") is null there.
		assertFalse(WebAuthMigration.migrateServers(null));
	}
}
