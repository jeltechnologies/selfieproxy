package online.selfieproxy.portal.web;

/**
 * What edit-local-website.html submits.
 *
 * @param label  the subdomain label
 * @param domain the chosen registered domain
 */
public record LocalWebsiteForm(String label, String domain) {
}
