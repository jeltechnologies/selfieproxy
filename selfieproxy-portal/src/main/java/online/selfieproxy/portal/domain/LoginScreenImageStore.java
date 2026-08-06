package online.selfieproxy.portal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * The two uploaded background images (light/dark mode) for
 * selfieproxy-identity-provider's login/change-password/logged-out pages -- separate from
 * LoginScreenSettingsStore's JSON since these are binary files, stored under
 * data/selfieproxy/login-screen/ with a fixed name per mode
 * ("background-light.&lt;ext&gt;"/"background-dark.&lt;ext&gt;"). The caller (LoginScreenController)
 * is responsible for persisting the returned filename into LoginScreenSettings.
 */
@Component
public class LoginScreenImageStore {

	private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE = Map.of(
			"image/png", "png",
			"image/jpeg", "jpg",
			"image/gif", "gif",
			"image/webp", "webp");

	private final Path imagesDir;

	public LoginScreenImageStore(@Value("${selfieproxy.login-screen-images-path}") String path) {
		this.imagesDir = Path.of(path);
	}

	/** Returns the extension for a content-type this store accepts, or null if the type isn't supported. */
	public String extensionFor(String contentType) {
		return contentType == null ? null : EXTENSION_BY_CONTENT_TYPE.get(contentType);
	}

	/**
	 * Writes file as the background image for mode ("light"/"dark"), removing previousFilename
	 * first if given (its extension may differ from the new upload's). Returns the new filename
	 * to persist in LoginScreenSettings. Throws IllegalArgumentException if file's content-type
	 * isn't one of the supported image types.
	 */
	public String save(String mode, MultipartFile file, String previousFilename) {
		String extension = extensionFor(file.getContentType());
		if (extension == null) {
			throw new IllegalArgumentException("Unsupported image type: " + file.getContentType());
		}
		String filename = "background-" + mode + "." + extension;
		try {
			Files.createDirectories(imagesDir);
			if (previousFilename != null) {
				Files.deleteIfExists(imagesDir.resolve(previousFilename));
			}
			Path target = imagesDir.resolve(filename);
			Files.deleteIfExists(target);
			file.transferTo(target);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to save " + mode + " background image", e);
		}
		return filename;
	}

	/** Removes the stored file for filename, if it exists. No-op if filename is null. */
	public void remove(String filename) {
		if (filename == null) {
			return;
		}
		try {
			Files.deleteIfExists(imagesDir.resolve(filename));
		} catch (IOException e) {
			throw new IllegalStateException("Failed to remove background image " + filename, e);
		}
	}
}
