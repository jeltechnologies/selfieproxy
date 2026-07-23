package online.selfieproxy.portal.domain;

import org.springframework.stereotype.Component;

import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.LocalWebsiteDemoProperties;

/**
 * Whether the two Local Websites LocalWebsiteDemoBootstrap creates on first boot are still around
 * in their original bootstrapped shape -- used by LocalWebsiteController to show a "this is just
 * demo content, safe to delete or replace" hint on the Local Websites list page. The content check
 * is a plain read of LocalWebsite.demo() -- a flag LocalWebsiteDemoBootstrap sets true when it
 * creates the site and LocalWebsiteController clears the moment a ZIP is uploaded to it -- rather
 * than comparing file contents on every page load; editing the content directory directly (eg. via
 * `docker exec`) isn't a documented user workflow, so it isn't accounted for here.
 */
@Component
public class LocalWebsiteDemoStatus {

	private final LocalWebsiteStore localWebsiteStore;
	private final BoringProxyProperties boringProxyProperties;
	private final LocalWebsiteDemoProperties properties;

	public LocalWebsiteDemoStatus(LocalWebsiteStore localWebsiteStore, BoringProxyProperties boringProxyProperties,
			LocalWebsiteDemoProperties properties) {
		this.localWebsiteStore = localWebsiteStore;
		this.boringProxyProperties = boringProxyProperties;
		this.properties = properties;
	}

	public String demoContentFqdn() {
		return properties.label() + "." + boringProxyProperties.primaryDomain();
	}

	public String demoRedirectDomain() {
		return boringProxyProperties.primaryDomain();
	}

	/** True while the demo content site still exists in content mode and its content has never been replaced. */
	public boolean isDemoContentUnmodified() {
		LocalWebsite website = localWebsiteStore.find(demoContentFqdn());
		return website != null && !website.isRedirect() && website.demo();
	}

	/** True while the apex redirect still exists and still points at the demo content site exactly as bootstrapped. */
	public boolean isDemoRedirectUnmodified() {
		LocalWebsite website = localWebsiteStore.find(demoRedirectDomain());
		return website != null && ("https://" + demoContentFqdn()).equals(website.redirectTo());
	}
}
