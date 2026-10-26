package online.selfieproxy.portal.boringproxy;

import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Certificates whose background renewal is failing, keyed by domain -- unlike CertPending (a
 * brand-new domain still waiting on its first certificate), these are still being served a valid
 * certificate right now, so nothing visibly breaks until it actually expires. Covers every
 * managed domain, including boringproxy's own fixed admin/portal/auth/console domains, which is
 * why fragments/layout.html's topbar reads it directly (as @certRenewalStatusService) rather
 * than each page's controller passing it in.
 */
@Component
public class CertRenewalStatusService {

	private static final Logger log = LoggerFactory.getLogger(CertRenewalStatusService.class);

	private final BoringProxyClient boringProxyClient;

	public CertRenewalStatusService(BoringProxyClient boringProxyClient) {
		this.boringProxyClient = boringProxyClient;
	}

	/** Sorted by domain; empty rather than failing the whole page if boringproxy can't be asked. */
	public Map<String, String> failures() {
		try {
			return new TreeMap<>(boringProxyClient.listCertRenewalFailures());
		} catch (RuntimeException e) {
			log.warn("Could not read certificate renewal failures from boringproxy: {}", e.getMessage());
			return Map.of();
		}
	}
}
