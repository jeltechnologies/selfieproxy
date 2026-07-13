package online.selfieproxy.identityprovider.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import online.selfieproxy.identityprovider.config.SsoProperties;

/**
 * Self-provisions this IdP's RSA signing keypair, mirroring
 * ThisServerBootstrap's check-disk/generate-if-absent/persist shape: on
 * first boot, generates a 2048-bit RSA keypair and PEM-encodes the private
 * key (PKCS8) to sso.signing-key-path (creating parent dirs); on every boot
 * -- including this first one -- (re)loads the keypair from that path, so a
 * restart keeps signing with the same key. Unlike ThisServerBootstrap's
 * secret file (which tolerates a transient dependency being down), a
 * read/parse failure of an *existing* key file is treated as real corruption
 * and is fatal -- this service cannot mint or verify tokens without it.
 */
@Component
public class SigningKeyProvider {

	private static final Logger log = LoggerFactory.getLogger(SigningKeyProvider.class);
	private static final int RSA_KEY_SIZE = 2048;
	private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
	private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";

	private final SsoProperties properties;
	private volatile RSAKey rsaKey;

	public SigningKeyProvider(SsoProperties properties) {
		this.properties = properties;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void loadOrGenerateKey() {
		Path path = Path.of(properties.signingKeyPath());
		try {
			if (!Files.exists(path)) {
				generateAndWrite(path);
				log.info("Generated new SSO signing keypair at {}", path);
			}
			this.rsaKey = load(path);
			log.info("Loaded SSO signing key (kid={}) from {}", rsaKey.getKeyID(), path);
		} catch (Exception e) {
			log.error("Failed to initialize SSO signing key at {}", path, e);
			throw new IllegalStateException("Failed to initialize SSO signing key at " + path, e);
		}
	}

	/** Public + private key material, keyed by a stable thumbprint-derived kid, for signing ID tokens and serving the JWKS/discovery endpoints. */
	public RSAKey getRsaKey() {
		return rsaKey;
	}

	private void generateAndWrite(Path path) throws NoSuchAlgorithmException, IOException {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(RSA_KEY_SIZE);
		KeyPair keyPair = generator.generateKeyPair();
		Files.createDirectories(path.getParent());
		Files.writeString(path, toPem(keyPair.getPrivate()));
	}

	private String toPem(PrivateKey key) {
		String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded());
		return PEM_HEADER + "\n" + base64 + "\n" + PEM_FOOTER + "\n";
	}

	/** RSA private keys generated via KeyPairGenerator are RSAPrivateCrtKey, which carries the public exponent alongside the private material -- so the public key can be reconstructed from the PKCS8 file alone, with nothing else persisted to disk. */
	private RSAKey load(Path path) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, JOSEException {
		String pem = Files.readString(path);
		String base64 = pem.replace(PEM_HEADER, "").replace(PEM_FOOTER, "").replaceAll("\\s", "");
		byte[] der = Base64.getDecoder().decode(base64);

		KeyFactory factory = KeyFactory.getInstance("RSA");
		RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
		RSAPublicKeySpec publicSpec = new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
		RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(publicSpec);

		return new RSAKey.Builder(publicKey)
				.privateKey(privateKey)
				.keyUse(KeyUse.SIGNATURE)
				.algorithm(JWSAlgorithm.RS256)
				.keyIDFromThumbprint()
				.build();
	}
}
