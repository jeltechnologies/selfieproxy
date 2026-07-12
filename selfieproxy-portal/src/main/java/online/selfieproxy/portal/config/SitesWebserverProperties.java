package online.selfieproxy.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Address and shared filesystem paths of the selfieproxy-sites NGINX
 * container that serves managed static sites -- see StaticSiteProvisioner.
 * host/port are what a managedStaticSite ExposedApp's Tunnel points at;
 * confPath/sitesPath are where selfieproxy-portal writes the per-domain
 * server-block file and ensures the content directory exists, both bind
 * mounts shared with that container.
 */
@ConfigurationProperties(prefix = "sites-webserver")
public record SitesWebserverProperties(String host, int port, String confPath, String sitesPath) {
}
