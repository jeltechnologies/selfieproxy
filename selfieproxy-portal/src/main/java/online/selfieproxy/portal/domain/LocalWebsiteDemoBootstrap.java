package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.config.LocalWebsiteDemoProperties;
import online.selfieproxy.portal.config.SitesWebserverProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;

/**
 * Creates two default Local Websites the first time the portal ever starts, then never again --
 * contentMarkerPath/redirectMarkerPath are written once each half of this bootstrap has run, so a
 * user who later deletes one of the two default sites does not get it silently recreated on the
 * next restart, independently of the other (see LocalWebsiteDemoProperties). Runs on
 * {@link ApplicationReadyEvent} for the same reason as AgentBootstrap: BoringProxyClient depends
 * on boringproxy's runtime token file already existing on disk.
 *
 * <p>The content site is populated from local-website-demo.zip, a single classpath resource the
 * build zips up from src/main/resources/local-website-demo/ (see pom.xml) -- fed straight into
 * StaticSiteProvisioner.replaceContents, the same already-tested ZIP-extraction/staging/
 * permission-fixing path a manual "upload ZIP" on the edit page already uses, rather than walking
 * classpath resources file-by-file at runtime.
 */
@Component
public class LocalWebsiteDemoBootstrap {

	private static final Logger log = LoggerFactory.getLogger(LocalWebsiteDemoBootstrap.class);
	private static final String OWNER = "admin";
	private static final String DEMO_ZIP_RESOURCE = "local-website-demo.zip";

	private final BoringProxyClient boringProxyClient;
	private final LocalWebsiteStore localWebsiteStore;
	private final StaticSiteProvisioner staticSiteProvisioner;
	private final SitesWebserverProperties sitesWebserverProperties;
	private final ThisServerAgentProperties thisServerAgentProperties;
	private final BoringProxyProperties boringProxyProperties;
	private final LocalWebsiteDemoProperties properties;

	public LocalWebsiteDemoBootstrap(BoringProxyClient boringProxyClient, LocalWebsiteStore localWebsiteStore,
			StaticSiteProvisioner staticSiteProvisioner, SitesWebserverProperties sitesWebserverProperties,
			ThisServerAgentProperties thisServerAgentProperties, BoringProxyProperties boringProxyProperties,
			LocalWebsiteDemoProperties properties) {
		this.boringProxyClient = boringProxyClient;
		this.localWebsiteStore = localWebsiteStore;
		this.staticSiteProvisioner = staticSiteProvisioner;
		this.sitesWebserverProperties = sitesWebserverProperties;
		this.thisServerAgentProperties = thisServerAgentProperties;
		this.boringProxyProperties = boringProxyProperties;
		this.properties = properties;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void createDefaultLocalWebsitesIfMissing() {
		bootstrapContentSite();
		bootstrapApexRedirect();
	}

	private void bootstrapContentSite() {
		Path markerPath = Path.of(properties.contentMarkerPath());
		if (Files.exists(markerPath)) {
			log.info("Local website demo content site bootstrap already ran previously, skipping.");
			return;
		}

		String fqdn = wwwFqdn();
		if (localWebsiteStore.find(fqdn) != null) {
			log.info("Local website '{}' already exists, skipping demo content bootstrap.", fqdn);
		} else {
			boringProxyClient.createTunnel(toCreateTunnelRequest(fqdn));
			staticSiteProvisioner.provision(fqdn, null);
			populateDemoContent(fqdn);
			localWebsiteStore.save(new LocalWebsite(properties.label(), boringProxyProperties.primaryDomain(), null, true));
			log.info("Created default Local Website demo content site at '{}'.", fqdn);
		}

		writeMarker(markerPath);
	}

	private void bootstrapApexRedirect() {
		Path markerPath = Path.of(properties.redirectMarkerPath());
		if (Files.exists(markerPath)) {
			log.info("Local website demo apex redirect bootstrap already ran previously, skipping.");
			return;
		}

		String domain = boringProxyProperties.primaryDomain();
		if (localWebsiteStore.find(domain) != null) {
			log.info("Local website '{}' already exists, skipping demo redirect bootstrap.", domain);
		} else {
			String redirectTo = "https://" + wwwFqdn();
			boringProxyClient.createTunnel(toCreateTunnelRequest(domain));
			staticSiteProvisioner.provision(domain, redirectTo);
			localWebsiteStore.save(new LocalWebsite("", domain, redirectTo, true));
			log.info("Created default Local Website redirect from '{}' to '{}'.", domain, redirectTo);
		}

		writeMarker(markerPath);
	}

	private String wwwFqdn() {
		return properties.label() + "." + boringProxyProperties.primaryDomain();
	}

	private void populateDemoContent(String fqdn) {
		try (InputStream in = new ClassPathResource(DEMO_ZIP_RESOURCE).getInputStream()) {
			staticSiteProvisioner.replaceContents(fqdn, in);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to load bundled " + DEMO_ZIP_RESOURCE, e);
		}
	}

	/** Mirrors LocalWebsiteController.toCreateTunnelRequest -- owner is hardcoded since there's no HTTP session at bootstrap time. */
	private CreateTunnelRequestDto toCreateTunnelRequest(String fqdn) {
		return new CreateTunnelRequestDto(
				fqdn,
				OWNER,
				thisServerAgentProperties.agentName(),
				sitesWebserverProperties.port(),
				sitesWebserverProperties.host(),
				null,
				null,
				null,
				null,
				null,
				"server",
				null,
				null,
				null);
	}

	private void writeMarker(Path markerPath) {
		try {
			Files.createDirectories(markerPath.getParent());
			Files.writeString(markerPath, "");
		} catch (IOException e) {
			throw new IllegalStateException("Failed to write local website demo bootstrap marker to " + markerPath, e);
		}
	}
}
