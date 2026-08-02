package online.selfieproxy.portal.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs a hard-to-miss startup warning whenever the host's sshd isn't configured to let Port
 * Forwarding tunnels actually work (see GatewayPortsChecker) -- printed unconditionally, regardless
 * of whether any stored Server currently has Port Forwarding enabled, since an admin might add one
 * later and this is cheap to check on every startup either way.
 */
@Component
public class GatewayPortsStartupWarning {

	private static final Logger log = LoggerFactory.getLogger(GatewayPortsStartupWarning.class);

	private final GatewayPortsChecker gatewayPortsChecker;

	public GatewayPortsStartupWarning(GatewayPortsChecker gatewayPortsChecker) {
		this.gatewayPortsChecker = gatewayPortsChecker;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void warnIfMisconfigured() {
		if (gatewayPortsChecker.isConfigured()) {
			return;
		}
		log.warn("=================================================================");
		log.warn(" WARNING: GatewayPorts is not configured in /etc/ssh/sshd_config.");
		log.warn(" Port Forwarding will not work until this is fixed. See the");
		log.warn(" README's Port Forwarding note for the one-line fix.");
		log.warn("=================================================================");
	}
}
