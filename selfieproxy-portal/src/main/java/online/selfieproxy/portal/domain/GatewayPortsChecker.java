package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether the host's real sshd (see selfieproxy.sshd-config-path) has GatewayPorts set to a value
 * that actually lets a Port Forwarding tunnel's SSH remote-forward bind a non-loopback address --
 * "yes" or "clientspecified", the two values that satisfy the "0.0.0.0" permitlisten restriction
 * TunnelManager.addToAuthorizedKeys writes for an AllowExternalTcp tunnel (selfieproxy-reverseproxy).
 * Its default, "no", silently forces every such remote-forward back to loopback-only regardless of
 * what's requested -- with no error anywhere, since the SSH session and the tunnel itself both
 * still succeed, only the actual bind address is wrong. Only Port Forwarding is affected; Web is
 * boringproxy's own :443 listener and Terminal/Remote Desktop deliberately request loopback anyway.
 *
 * Deliberately only parses the one mounted file, not any Include-d snippet under
 * sshd_config.d/ (stock Ubuntu's own shipped default has "Include /etc/ssh/sshd_config.d/*.conf" at
 * the top, and such a snippet can technically override the main file) -- this is an advisory
 * warning, not a hard gate, and mounting the whole /etc/ssh directory just to resolve Include isn't
 * worth the extra host-filesystem exposure.
 */
@Component
public class GatewayPortsChecker {

	private static final Logger log = LoggerFactory.getLogger(GatewayPortsChecker.class);

	private final Path sshdConfigPath;

	public GatewayPortsChecker(@Value("${selfieproxy.sshd-config-path}") String sshdConfigPath) {
		this.sshdConfigPath = Path.of(sshdConfigPath);
	}

	/** True if GatewayPorts is "yes"/"clientspecified"; false if it's "no", any other value, absent, or the file can't be read. */
	public boolean isConfigured() {
		List<String> lines;
		try {
			lines = Files.readAllLines(sshdConfigPath);
		} catch (IOException e) {
			log.debug("Could not read {} to check GatewayPorts: {}", sshdConfigPath, e.getMessage());
			return false;
		}
		// sshd_config's own documented rule: the first uncommented definition of an option wins,
		// later ones are ignored.
		for (String line : lines) {
			String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			String[] parts = trimmed.split("\\s+", 2);
			if (parts.length == 2 && parts[0].equalsIgnoreCase("GatewayPorts")) {
				String value = parts[1].strip();
				return value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("clientspecified");
			}
		}
		return false;
	}
}
