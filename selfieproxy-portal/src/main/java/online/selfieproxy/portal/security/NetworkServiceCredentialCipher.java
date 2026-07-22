package online.selfieproxy.portal.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts/decrypts an SSH/RDP/VNC-mode Network Service's credential (SSH
 * password or private key, RDP/VNC password) at rest in exposed-apps.json,
 * using a symmetric key self-provisioned into network-service-secret-key the
 * first time it's needed -- same idiom as selfieproxy-identity-provider's
 * sso-signing-key.pem and ThisServerBootstrap's secret republishing.
 * Host-specific by design: see BackupService, which strips encryptedSecret
 * from a configuration export since an exported ciphertext would be
 * undecryptable on a different server's key.
 */
@Component
public class NetworkServiceCredentialCipher {

	private static final String ALGORITHM = "AES/GCM/NoPadding";
	private static final int KEY_LENGTH_BYTES = 32;
	private static final int GCM_IV_LENGTH_BYTES = 12;
	private static final int GCM_TAG_LENGTH_BITS = 128;

	private final Path keyPath;
	private final SecureRandom random = new SecureRandom();
	private volatile SecretKeySpec key;

	public NetworkServiceCredentialCipher(@Value("${selfieproxy.network-service-key-path}") String keyPath) {
		this.keyPath = Path.of(keyPath);
	}

	public String encrypt(String plaintext) {
		if (plaintext == null || plaintext.isEmpty()) {
			return null;
		}
		try {
			byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
			random.nextBytes(iv);

			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

			ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
			buffer.put(iv).put(ciphertext);
			return Base64.getEncoder().encodeToString(buffer.array());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Failed to encrypt network service credential", e);
		}
	}

	/** Returns null for a null/blank input -- eg. an app with no stored credential yet. */
	public String decrypt(String encoded) {
		if (encoded == null || encoded.isEmpty()) {
			return null;
		}
		try {
			byte[] raw = Base64.getDecoder().decode(encoded);
			ByteBuffer buffer = ByteBuffer.wrap(raw);
			byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
			buffer.get(iv);
			byte[] ciphertext = new byte[buffer.remaining()];
			buffer.get(ciphertext);

			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
			return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Failed to decrypt network service credential", e);
		}
	}

	private SecretKeySpec key() {
		SecretKeySpec loaded = key;
		if (loaded != null) {
			return loaded;
		}
		synchronized (this) {
			if (key == null) {
				key = new SecretKeySpec(loadOrCreateKeyBytes(), "AES");
			}
			return key;
		}
	}

	private byte[] loadOrCreateKeyBytes() {
		try {
			if (Files.exists(keyPath)) {
				byte[] existing = Files.readAllBytes(keyPath);
				if (existing.length == KEY_LENGTH_BYTES) {
					return existing;
				}
			}
			byte[] generated = new byte[KEY_LENGTH_BYTES];
			random.nextBytes(generated);
			Files.createDirectories(keyPath.getParent());
			Files.write(keyPath, generated);
			return generated;
		} catch (IOException e) {
			throw new IllegalStateException("Failed to load or create " + keyPath, e);
		}
	}
}
