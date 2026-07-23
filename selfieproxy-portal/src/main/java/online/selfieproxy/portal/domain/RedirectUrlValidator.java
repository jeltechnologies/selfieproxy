package online.selfieproxy.portal.domain;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Validates a Local Website's redirect target (see LocalWebsite.redirectTo()): a bare
 * {@code http(s)://host} with no path/query/fragment. Restricted to scheme+host only because
 * StaticSiteProvisioner's generated NGINX block appends the visited path onto it verbatim
 * ({@code return 301 <redirectTo>$request_uri;}) -- allowing a path here would make that
 * concatenation ambiguous, and the feature is meant to point a whole domain elsewhere, not
 * rewrite individual URLs.
 */
public final class RedirectUrlValidator {

	private RedirectUrlValidator() {
	}

	public static boolean isValid(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			return false;
		}
		String scheme = uri.getScheme();
		String path = uri.getPath();
		return uri.getHost() != null
				&& ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
				&& uri.getQuery() == null
				&& uri.getFragment() == null
				&& (path == null || path.isEmpty() || path.equals("/"));
	}
}
