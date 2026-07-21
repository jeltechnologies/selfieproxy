package online.selfieproxy.portal.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import online.selfieproxy.portal.config.BoringProxyProperties;

/**
 * Everything domain-related in one place: which domains exist (the primary
 * domain, fixed at boot, plus every registered secondary domain -- see
 * DomainStore), and whether each one's DNS is actually pointed at this
 * server. The single source of truth for the domain <select> that appears on
 * the Add Application/Add Local Website pages, the restore wizard, and the
 * Domains settings page.
 */
@Component
public class DomainService {

	private static final Logger log = LoggerFactory.getLogger(DomainService.class);

	public enum DomainStatus {
		PRIMARY, OK, ERROR
	}

	/**
	 * @param name    the full domain
	 * @param primary true only for the one fixed primary domain
	 */
	public record Domain(String name, boolean primary) {
	}

	private final DomainStore domainStore;
	private final BoringProxyProperties properties;

	public DomainService(DomainStore domainStore, BoringProxyProperties properties) {
		this.domainStore = domainStore;
		this.properties = properties;
	}

	public String primaryDomain() {
		return properties.primaryDomain();
	}

	/** Primary domain first, then every secondary domain alphabetically -- the one ordering every domain <select>/list page uses. */
	public List<Domain> allDomains() {
		List<Domain> result = new ArrayList<>();
		result.add(new Domain(properties.primaryDomain(), true));
		domainStore.list().forEach(d -> result.add(new Domain(d.name(), false)));
		return result;
	}

	public boolean exists(String domainName) {
		if (domainName == null) {
			return false;
		}
		return properties.primaryDomain().equalsIgnoreCase(domainName) || domainStore.find(domainName) != null;
	}

	/**
	 * This server's own public IP, resolved via the primary domain rather than an external lookup
	 * (e.g. ifconfig.me) -- check-prerequisites already validated *.PRIMARY_DOMAIN resolves here at
	 * container startup, so the primary domain itself is already a trusted way to find "our own IP".
	 * Null if resolution fails for some reason.
	 */
	public String serverIp() {
		return resolveIp(properties.primaryDomain());
	}

	public String resolveIp(String hostname) {
		try {
			return InetAddress.getByName(hostname).getHostAddress();
		} catch (UnknownHostException e) {
			log.warn("Could not resolve {} to check its DNS records", hostname, e);
			return null;
		}
	}

	/** True when domainName's own DNS doesn't resolve to this server at all, or resolves somewhere else. serverIp null (couldn't resolve our own primary domain) never reports a mismatch, to avoid a false positive. */
	public boolean hasDnsMismatch(String domainName, String serverIp) {
		if (serverIp == null) {
			return false;
		}
		return !serverIp.equals(resolveIp(domainName));
	}

	/** PRIMARY unconditionally for the primary domain (no DNS check attempted); OK/ERROR by IP comparison against serverIp() for every other domain. */
	public DomainStatus statusOf(String domainName) {
		if (properties.primaryDomain().equalsIgnoreCase(domainName)) {
			return DomainStatus.PRIMARY;
		}
		String serverIp = serverIp();
		if (serverIp == null) {
			return DomainStatus.OK;
		}
		return hasDnsMismatch(domainName, serverIp) ? DomainStatus.ERROR : DomainStatus.OK;
	}

	/** Plain-language explanation of where domainName's DNS currently points vs. where it needs to, for the Domains settings page's rename warning. Only meaningful when statusOf(domainName) is ERROR. */
	public String dnsExplanation(String domainName) {
		String server = serverIp();
		String resolved = resolveIp(domainName);
		if (resolved == null) {
			return "This domain's DNS records don't point anywhere yet. Update its DNS settings"
					+ (server != null ? " (usually an \"A\" record) to point to this server's address, " + server + "." : ".");
		}
		return "This domain currently points to " + resolved
				+ (server != null ? ", but this server is at " + server + ". Update this domain's DNS settings (usually an \"A\" record) to point to " + server + " instead."
						: ", which doesn't match this server's own address.");
	}
}
