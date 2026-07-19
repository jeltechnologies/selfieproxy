package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip-slip-guarded extraction and directory-to-entries writing, shared by
 * StaticSiteProvisioner (a single site's own ZIP) and BackupService (many
 * sites' content folders inside one larger backup ZIP).
 */
final class ZipUtils {

	private ZipUtils() {
	}

	/** Extracts zipData into targetDir (assumed to already exist and be empty). Rejects entries that would escape targetDir (zip-slip). */
	static void extract(InputStream zipData, Path targetDir) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(zipData)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				Path target = targetDir.resolve(entry.getName()).normalize();
				if (!target.startsWith(targetDir)) {
					throw new IOException("ZIP entry escapes target directory: " + entry.getName());
				}
				if (entry.isDirectory()) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
				}
				zip.closeEntry();
			}
		}
	}

	/** Writes every regular file under dir into zip, entry names prefixed with entryPrefix (empty for no prefix) and made relative to dir's root. A missing directory writes nothing. */
	static void writeDirectoryEntries(Path dir, String entryPrefix, ZipOutputStream zip) throws IOException {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir).filter(Files::isRegularFile)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				String entryName = entryPrefix + dir.relativize(file).toString().replace('\\', '/');
				zip.putNextEntry(new ZipEntry(entryName));
				Files.copy(file, zip);
				zip.closeEntry();
			}
		}
	}

	static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.delete(path);
				} catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			});
		} catch (java.io.UncheckedIOException e) {
			throw e.getCause();
		}
	}
}
