package online.selfieproxy.remoteconsole.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decrypt-only counterpart to selfieproxy-portal's own
 * RemoteConsoleCredentialCipher -- reads the same self-provisioned key
 * (remote-console-secret-key, shared /data volume) that module writes, but
 * never generates or writes it here: this service never accepts new
 * credentials from a user, only decrypts what the portal already encrypted.
 * The AES/GCM parameters (key length, IV length, tag length, wire layout
 * {@code iv || ciphertext}) must stay byte-for-byte identical between the two
 * modules.
 */
@Component
public class RemoteConsoleCredentialCipher {

	private static final String ALGORITHM = "AES/GCM/NoPadding";
	private static final int GCM_IV_LENGTH_BYTES = 12;
	private static final int GCM_TAG_LENGTH_BITS = 128;

	private final Path keyPath;
	private volatile SecretKeySpec key;

	public RemoteConsoleCredentialCipher(@Value("${selfieproxy.remote-console-key-path}") String keyPath) {
		this.keyPath = Path.of(keyPath);
	}

	/** Returns null for a null/blank input -- eg. a console with no stored credential. */
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
			return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Failed to decrypt Remote Console credential", e);
		}
	}

	private SecretKeySpec key() {
		SecretKeySpec loaded = key;
		if (loaded != null) {
			return loaded;
		}
		synchronized (this) {
			if (key == null) {
				try {
					key = new SecretKeySpec(Files.readAllBytes(keyPath), "AES");
				} catch (java.io.IOException e) {
					throw new IllegalStateException(
							"Failed to read " + keyPath + " -- has selfieproxy-portal provisioned it yet?", e);
				}
			}
			return key;
		}
	}
}
