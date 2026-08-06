package online.selfieproxy.identityprovider.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import online.selfieproxy.identityprovider.domain.LoginScreenSettings;
import online.selfieproxy.identityprovider.domain.LoginScreenSettingsStore;

/**
 * Streams the uploaded light/dark login-screen background image (see
 * selfieproxy-portal's LoginScreenImageStore, which writes these files over the shared /data
 * volume) to the actual login/change-password/logged-out pages, referenced via
 * GlobalModelAttributes' loginScreenBackgroundUrl. No Spring resource-handler exists anywhere in
 * this repo -- a small streaming @GetMapping keeps this consistent with
 * selfieproxy-portal's own LocalWebsiteController.download rather than introducing one.
 */
@RestController
public class LoginScreenAssetController {

	private final LoginScreenSettingsStore settingsStore;
	private final Path imagesDir;

	public LoginScreenAssetController(LoginScreenSettingsStore settingsStore,
			@Value("${selfieproxy.login-screen-images-path}") String imagesPath) {
		this.settingsStore = settingsStore;
		this.imagesDir = Path.of(imagesPath);
	}

	@GetMapping("/login-screen/background/{mode}")
	public ResponseEntity<byte[]> background(@PathVariable String mode) throws IOException {
		LoginScreenSettings settings = settingsStore.load();
		String filename = "light".equals(mode) ? settings.backgroundLightFilename()
				: "dark".equals(mode) ? settings.backgroundDarkFilename() : null;
		if (filename == null) {
			return ResponseEntity.notFound().build();
		}
		Path file = imagesDir.resolve(filename);
		if (!Files.exists(file)) {
			return ResponseEntity.notFound().build();
		}
		String contentType = Files.probeContentType(file);
		return ResponseEntity.ok()
				.contentType(contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM)
				.body(Files.readAllBytes(file));
	}
}
