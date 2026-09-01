package online.selfieproxy.portal.domain;

import java.util.List;

/**
 * The "Advanced" exceptions list under a Server's Authentication methods -- URL path patterns that
 * reach the homelab server without passing whichever gate the Server uses (see {@link WebAuthMethod}).
 * Parsing and validation live here rather than in ServerController for the same reason
 * {@link DnsLabelValidator}/{@link RedirectUrlValidator} do: a rule about what an admin may type is
 * worth testing on its own, not only through a full MVC round trip.
 *
 * <p>The patterns themselves are matched by the reverse proxy, not here -- see globMatch and
 * authPathExempt in its http_proxy.go. This side only rejects what could never mean what the admin
 * intended.
 */
public final class AuthExemptPaths {

	/** Capped only to keep one Server from bloating every tunnel poll the agents make. */
	public static final int MAX_ENTRIES = 20;
	public static final int MAX_LENGTH = 200;

	private AuthExemptPaths() {
	}

	/**
	 * Tidies what the rows submitted. Empty entries are dropped rather than rejected (the always-
	 * present trailing blank row submits one every time, and a row the admin cleared instead of
	 * removing is the same intent) and duplicates are collapsed, but the admin's own order is kept
	 * -- a list that comes back reordered after every save reads like the page mangled it.
	 */
	public static List<String> parse(List<String> submitted) {
		if (submitted == null || submitted.isEmpty()) {
			return List.of();
		}
		return submitted.stream()
				.filter(java.util.Objects::nonNull)
				.map(String::trim)
				.filter(entry -> !entry.isEmpty())
				.distinct()
				.toList();
	}

	/**
	 * Everything wrong with a list, as messages ready to show on the edit page. Only two things are
	 * worth catching: a pattern that isn't a rooted path can never match anything the reverse proxy
	 * compares it against, and a pattern matching every path silently turns the selected gate off --
	 * which is a legitimate thing to want, but there is already an explicit "Not protected" radio
	 * for it, and reaching it by way of a wildcard hides that decision from anyone reading the page
	 * later.
	 */
	public static List<String> validate(List<String> paths) {
		if (paths == null || paths.isEmpty()) {
			return List.of();
		}

		List<String> errors = new java.util.ArrayList<>();
		if (paths.size() > MAX_ENTRIES) {
			errors.add("Up to " + MAX_ENTRIES + " authentication exceptions can be added.");
		}
		for (String pattern : paths) {
			if (!pattern.startsWith("/")) {
				errors.add("Authentication exception \"" + pattern + "\" must start with a slash.");
			} else if (pattern.length() > MAX_LENGTH) {
				errors.add("An authentication exception cannot be longer than " + MAX_LENGTH + " characters.");
			} else if (matchesEverything(pattern)) {
				errors.add("Authentication exception \"" + pattern
						+ "\" would leave the whole server open. Select \"Not protected\" instead if that is what you want.");
			} else if (containsIllegalCharacter(pattern)) {
				errors.add("Authentication exception \"" + pattern + "\" cannot contain spaces or control characters.");
			}
		}
		return errors;
	}

	/** "/", "/*" and "/**" each match every path the Server can serve, gate included. */
	private static boolean matchesEverything(String pattern) {
		return pattern.equals("/") || pattern.equals("/*") || pattern.equals("/**");
	}

	/**
	 * A space would silently never match (the reverse proxy compares against an already-decoded
	 * request path, in which a real space arrives as %20 only in the raw URL), and a control
	 * character has no business in a path at all.
	 */
	private static boolean containsIllegalCharacter(String pattern) {
		for (int i = 0; i < pattern.length(); i++) {
			char c = pattern.charAt(i);
			if (c <= ' ' || c == 127) {
				return true;
			}
		}
		return false;
	}
}
