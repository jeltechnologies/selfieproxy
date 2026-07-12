package online.selfieproxy.portal.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import online.selfieproxy.portal.config.BoringProxyProperties;

/** Logs the portal's own URL once fully started, so it's easy to find and paste into a browser without hunting through .env for SELFPROXY_ADMIN_DOMAIN/DOMAIN. */
@Component
public class PortalUrlLogger {

	private static final Logger log = LoggerFactory.getLogger(PortalUrlLogger.class);

	private final BoringProxyProperties properties;

	public PortalUrlLogger(BoringProxyProperties properties) {
		this.properties = properties;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void logPortalUrl() {
		log.info("Selfie Proxy admin portal is ready at https://{}", properties.portalDomain());
	}
}
