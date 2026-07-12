package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import online.selfieproxy.portal.config.SitesWebserverProperties;

/**
 * Provisions the on-disk pieces a LocalWebsite needs: a content directory the
 * user drops files into, and an NGINX server-block file picking it by domain
 * for the shared selfieproxy-sites container (see sites-webserver/). Pure
 * filesystem I/O, same shape as ExposedAppStore -- selfieproxy-portal never
 * talks to that container directly, it just writes files selfieproxy-sites'
 * own entrypoint watches and reloads on.
 */
@Component
public class StaticSiteProvisioner {

	private final Path confDir;
	private final Path sitesDir;

	public StaticSiteProvisioner(SitesWebserverProperties properties) {
		this.confDir = Path.of(properties.confPath());
		this.sitesDir = Path.of(properties.sitesPath());
	}

	/** Ensures domain's content directory exists and (re)writes its NGINX server block. */
	public void provision(String domain) {
		try {
			Files.createDirectories(sitesDir.resolve(domain));
			Files.createDirectories(confDir);
			Files.writeString(confFile(domain), """
					server {
						listen 80;
						server_name %s;
						root /sites/%s;
						index index.html;
					}
					""".formatted(domain, domain));
		} catch (IOException e) {
			throw new IllegalStateException("Failed to provision static site for " + domain, e);
		}
	}

	/** Removes domain's NGINX server block -- disables routing to it. The content directory is left intact -- no destructive delete of user files by default. */
	public void deprovision(String domain) {
		try {
			Files.deleteIfExists(confFile(domain));
		} catch (IOException e) {
			throw new IllegalStateException("Failed to remove static site config for " + domain, e);
		}
	}

	/**
	 * Moves oldDomain's content directory to newDomain and re-provisions under
	 * the new name. If a directory for newDomain already exists (eg. leftover
	 * from a previous website at that domain), it's left as-is and reused
	 * instead of being overwritten -- same "reuse what's already there"
	 * behavior as provision() ensuring rather than clearing a directory.
	 */
	public void rename(String oldDomain, String newDomain) {
		try {
			Files.deleteIfExists(confFile(oldDomain));
			Path oldDir = sitesDir.resolve(oldDomain);
			Path newDir = sitesDir.resolve(newDomain);
			if (Files.exists(oldDir) && !Files.exists(newDir)) {
				Files.move(oldDir, newDir);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Failed to rename static site from " + oldDomain + " to " + newDomain, e);
		}
		provision(newDomain);
	}

	private Path confFile(String domain) {
		return confDir.resolve(domain + ".conf");
	}
}
