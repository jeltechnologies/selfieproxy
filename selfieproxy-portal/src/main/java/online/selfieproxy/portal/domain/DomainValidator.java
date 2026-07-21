package online.selfieproxy.portal.domain;

import java.util.regex.Pattern;

/**
 * RFC 1123 syntax for a whole multi-label domain name: one or more
 * dot-separated labels, each satisfying the same single-label rule
 * DnsLabelValidator already applies to subdomains, no leading/trailing dot,
 * max 253 characters overall. Used for the Domains settings page (add/rename
 * a secondary domain) -- NOT for an Exposed App/Local Website subdomain
 * label, which stays a single DnsLabelValidator label composed onto a chosen
 * domain (see DomainService), nor for a Local Website's "Other domain" field,
 * which keeps accepting any syntactically valid domain without requiring it
 * be registered here.
 */
public final class DomainValidator {

	private static final Pattern VALID_DOMAIN = Pattern
			.compile("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

	private DomainValidator() {
	}

	public static boolean isValid(String domain) {
		return domain != null && domain.length() <= 253 && VALID_DOMAIN.matcher(domain).matches();
	}
}
