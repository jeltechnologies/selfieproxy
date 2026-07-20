package online.selfieproxy.portal.domain;

import java.util.regex.Pattern;

/**
 * RFC 1123's "preferred name syntax" for a single DNS label: ASCII letters, digits, and hyphens
 * only (1-63 characters), never starting or ending with a hyphen -- no dots, spaces, or any other
 * punctuation. Used for both an Exposed App/Local Website subdomain (which becomes a real DNS
 * label under DOMAIN) and a Homelab name (never itself a DNS label, but validated the same way for
 * consistency, per selfieproxy-portal/CLAUDE.md) -- shared by the ordinary add/edit forms
 * (AgentController/ExposedAppController/LocalWebsiteController) and the import wizard's apply step
 * (BackupService), so a crafted or pre-validation configuration export can't smuggle in a name an
 * ordinary form would have rejected.
 */
public final class DnsLabelValidator {

	private static final Pattern VALID_LABEL = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");

	private DnsLabelValidator() {
	}

	public static boolean isValid(String label) {
		return label != null && VALID_LABEL.matcher(label).matches();
	}
}
