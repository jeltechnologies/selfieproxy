package online.selfieproxy.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LOCAL_WEBSITE_DEMO_LABEL in .env -- the subdomain label LocalWebsiteDemoBootstrap uses for the
 * bundled "Local website demo" content site (www.PRIMARY_DOMAIN by default) it creates
 * automatically the first time the portal ever starts, plus an apex redirect from the bare
 * PRIMARY_DOMAIN to it. contentMarkerPath/redirectMarkerPath each independently track whether
 * that half of the one-time bootstrap already ran, so a user who later deletes just one of the
 * two does not get it silently recreated on a subsequent restart, and the other is unaffected --
 * see AgentDefaultsProperties for the same single-marker idiom, applied here twice over.
 */
@ConfigurationProperties(prefix = "local-website-demo")
public record LocalWebsiteDemoProperties(String label, String contentMarkerPath, String redirectMarkerPath) {
}
