package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import online.selfieproxy.portal.config.SitesWebserverProperties;

/**
 * Provisions the on-disk pieces a LocalWebsite needs: a content directory the
 * user drops files into, and an NGINX server-block file picking it by domain
 * for the shared selfieproxy-local-websites container (see sites-webserver/). Pure
 * filesystem I/O, same shape as ExposedAppStore -- selfieproxy-portal never
 * talks to that container directly, it just writes files selfieproxy-local-websites'
 * own entrypoint watches and reloads on.
 */
@Component
public class StaticSiteProvisioner {

	private static final Logger log = LoggerFactory.getLogger(StaticSiteProvisioner.class);

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

	/** Removes domain's NGINX server block and permanently deletes its entire content directory -- a destructive operation, the site's files are gone. */
	public void remove(String domain) {
		try {
			Files.deleteIfExists(confFile(domain));
			ZipUtils.deleteRecursively(sitesDir.resolve(domain));
		} catch (IOException e) {
			throw new IllegalStateException("Failed to remove static site for " + domain, e);
		}
	}

	/**
	 * Moves oldDomain's content directory to newDomain and re-provisions under
	 * the new name. If a directory for newDomain already exists (eg. a race
	 * with a concurrent add under that same domain), it's left as-is and
	 * reused instead of being overwritten.
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

	/** Zips domain's content directory to out, entries relative to the directory root (no leading domain folder). A missing directory yields an empty zip. */
	public void writeZip(String domain, OutputStream out) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			writeEntries(domain, "", zip);
		}
	}

	/** Writes domain's content directory into an already-open zip, entry names prefixed with entryPrefix -- used to embed several sites' content into one larger backup ZIP (see BackupService); writeZip is for a single site's standalone download. */
	public void writeEntries(String domain, String entryPrefix, ZipOutputStream zip) throws IOException {
		ZipUtils.writeDirectoryEntries(sitesDir.resolve(domain), entryPrefix, zip);
	}

	/**
	 * Replaces domain's entire content directory with the contents of zipData.
	 * The upload is fully extracted into a staging directory first; only once
	 * that succeeds completely are the existing files deleted and the staged
	 * ones swapped in (a same-filesystem directory rename, so the swap itself
	 * can't partially apply). If the upload or the ZIP is bad in any way, this
	 * throws before touching the existing content directory at all.
	 */
	public void replaceContents(String domain, InputStream zipData) {
		Path dir = sitesDir.resolve(domain);
		Path stagingDir;
		try {
			stagingDir = Files.createTempDirectory(sitesDir, "upload-staging-");
		} catch (IOException e) {
			throw new IllegalStateException("Failed to prepare upload staging area for " + domain, e);
		}
		try {
			ZipUtils.extract(zipData, stagingDir);
			Path contentRoot = unwrapSingleRootDirectory(stagingDir);
			ZipUtils.deleteRecursively(dir);
			Files.move(contentRoot, dir);
			// createTempDirectory defaults to owner-only (700) permissions -- fine for the portal
			// container itself, but selfieproxy-local-websites' NGINX runs as a different user in
			// its own container and needs to at least traverse into the directory to serve it.
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
		} catch (IOException e) {
			throw new IllegalStateException(
					"Failed to extract uploaded ZIP for " + domain + " -- existing files were left untouched", e);
		} finally {
			// Safe even when contentRoot was moved out from under extractedDir above
			// (rename leaves extractedDir either gone or an empty husk) -- deleteRecursively
			// no-ops on a path that no longer exists.
			deleteQuietly(stagingDir);
		}
	}

	/**
	 * Many ZIP tools (and "Download ZIP" on GitHub, etc.) wrap a site's files
	 * in a single named folder instead of zipping the files themselves at the
	 * root. If the extracted upload contains exactly one top-level entry and
	 * it's a directory, its contents become the site root instead of that
	 * wrapper folder itself -- otherwise the site would be served at
	 * /<wrapper-name>/index.html instead of /index.html.
	 */
	private static Path unwrapSingleRootDirectory(Path extractedDir) throws IOException {
		try (Stream<Path> entries = Files.list(extractedDir)) {
			List<Path> topLevel = entries.toList();
			if (topLevel.size() == 1 && Files.isDirectory(topLevel.get(0))) {
				return topLevel.get(0);
			}
		}
		return extractedDir;
	}

	private void deleteQuietly(Path dir) {
		try {
			ZipUtils.deleteRecursively(dir);
		} catch (IOException e) {
			log.warn("Failed to clean up upload staging directory {}", dir, e);
		}
	}

	private Path confFile(String domain) {
		return confDir.resolve(domain + ".conf");
	}
}
