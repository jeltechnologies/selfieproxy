package online.selfieproxy.portal.web;

import online.selfieproxy.portal.domain.LocalWebsiteType;

/**
 * What edit-local-website.html submits.
 *
 * @param label      the subdomain label
 * @param domain     the chosen registered domain
 * @param type       CONTENT (default) or REDIRECT
 * @param redirectTo only meaningful when type is REDIRECT
 */
public record LocalWebsiteForm(String label, String domain, LocalWebsiteType type, String redirectTo) {
}
