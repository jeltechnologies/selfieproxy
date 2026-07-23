package online.selfieproxy.portal.domain;

import java.util.regex.Pattern;

/**
 * Turns a Network Service's user-entered Name into a safe, human-readable boringproxy tunnel
 * subdomain -- lowercased, with anything outside {@code [a-z0-9]} collapsed to a single hyphen.
 * The result always satisfies {@link DnsLabelValidator#isValid}.
 */
public final class NetworkServiceLabel {

	private static final Pattern UNSAFE_RUN = Pattern.compile("[^a-z0-9]+");
	// RFC1123's 63-char label max, minus room for a "-<n>" collision suffix (see
	// ExposedAppController.generateSubdomain) so an appended suffix never pushes the whole label over the limit.
	private static final int MAX_LENGTH = 59;

	private NetworkServiceLabel() {
	}

	public static String slugify(String name) {
		String slug = UNSAFE_RUN.matcher(name.trim().toLowerCase()).replaceAll("-");
		slug = strip(slug);
		if (slug.length() > MAX_LENGTH) {
			slug = strip(slug.substring(0, MAX_LENGTH));
		}
		return slug.isEmpty() ? "service" : slug;
	}

	private static String strip(String slug) {
		int start = 0;
		int end = slug.length();
		while (start < end && slug.charAt(start) == '-') {
			start++;
		}
		while (end > start && slug.charAt(end - 1) == '-') {
			end--;
		}
		return slug.substring(start, end);
	}
}
