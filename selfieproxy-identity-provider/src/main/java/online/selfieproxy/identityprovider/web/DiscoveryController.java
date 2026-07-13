package online.selfieproxy.identityprovider.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import online.selfieproxy.identityprovider.config.SsoProperties;
import online.selfieproxy.identityprovider.domain.SigningKeyProvider;

/** OIDC discovery document and JWKS -- the only "client registration" this IdP does is the single hardcoded public client, so there is no dynamic registration endpoint here. */
@RestController
public class DiscoveryController {

	private final SsoProperties properties;
	private final SigningKeyProvider signingKeyProvider;

	public DiscoveryController(SsoProperties properties, SigningKeyProvider signingKeyProvider) {
		this.properties = properties;
		this.signingKeyProvider = signingKeyProvider;
	}

	@GetMapping("/.well-known/openid-configuration")
	public Map<String, Object> discovery() {
		String issuer = properties.issuerUrl();
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("issuer", issuer);
		doc.put("authorization_endpoint", issuer + "/authorize");
		doc.put("token_endpoint", issuer + "/token");
		doc.put("jwks_uri", issuer + "/jwks.json");
		doc.put("response_types_supported", List.of("code"));
		doc.put("subject_types_supported", List.of("public"));
		doc.put("id_token_signing_alg_values_supported", List.of("RS256"));
		doc.put("scopes_supported", List.of("openid", "profile", "email"));
		doc.put("token_endpoint_auth_methods_supported", List.of("none"));
		doc.put("code_challenge_methods_supported", List.of("S256"));
		return doc;
	}

	@GetMapping("/jwks.json")
	public Map<String, Object> jwks() {
		return Map.of("keys", List.of(signingKeyProvider.getRsaKey().toPublicJWK().toJSONObject()));
	}
}
