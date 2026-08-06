package online.selfieproxy.portal.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import online.selfieproxy.portal.config.OidcProperties;
import online.selfieproxy.portal.domain.LoginScreenImageStore;
import online.selfieproxy.portal.domain.LoginScreenSettings;
import online.selfieproxy.portal.domain.LoginScreenSettingsStore;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The "Login screen" Settings-menu page: lets the admin brand
 * selfieproxy-identity-provider's login/change-password/logged-out pages (title, slogan,
 * username/password labels, logo/footer on-off, per-theme background image). Only meaningful
 * while the bundled IdP is doing the authenticating -- hidden from the Settings menu (see
 * fragments/layout.html's showUsersLink) and rejected here too, defense in depth, whenever an
 * external OIDC issuer is configured (OidcProperties.isExternal()).
 */
@Controller
public class LoginScreenController {

	private static final Set<String> MODES = Set.of("light", "dark");

	private final LoginScreenSettingsStore settingsStore;
	private final LoginScreenImageStore imageStore;
	private final OidcProperties oidcProperties;
	private final Path imagesDir;

	public LoginScreenController(LoginScreenSettingsStore settingsStore, LoginScreenImageStore imageStore,
			OidcProperties oidcProperties, @Value("${selfieproxy.login-screen-images-path}") String imagesPath) {
		this.settingsStore = settingsStore;
		this.imageStore = imageStore;
		this.oidcProperties = oidcProperties;
		this.imagesDir = Path.of(imagesPath);
	}

	@GetMapping("/login-screen")
	public String page(Model model, HttpServletResponse response) throws IOException {
		if (blockExternalOidc(response)) {
			return null;
		}
		model.addAttribute("settings", settingsStore.load());
		return "login-screen";
	}

	@PostMapping("/login-screen")
	public String save(@RequestParam(defaultValue = "false") boolean logoEnabled,
			@RequestParam(defaultValue = "") String title, @RequestParam(defaultValue = "") String slogan,
			@RequestParam(defaultValue = "") String usernameLabel, @RequestParam(defaultValue = "") String passwordLabel,
			@RequestParam(defaultValue = "") String loginButtonLabel,
			@RequestParam(defaultValue = "false") boolean footerEnabled,
			HttpServletResponse response) throws IOException {
		if (blockExternalOidc(response)) {
			return null;
		}
		LoginScreenSettings current = settingsStore.load();
		settingsStore.save(new LoginScreenSettings(
				logoEnabled,
				blankToDefault(title, LoginScreenSettings.DEFAULT_TITLE),
				blankToDefault(slogan, LoginScreenSettings.DEFAULT_SLOGAN),
				blankToDefault(usernameLabel, LoginScreenSettings.DEFAULT_USERNAME_LABEL),
				blankToDefault(passwordLabel, LoginScreenSettings.DEFAULT_PASSWORD_LABEL),
				blankToDefault(loginButtonLabel, LoginScreenSettings.DEFAULT_LOGIN_BUTTON_LABEL),
				footerEnabled,
				current.backgroundLightFilename(),
				current.backgroundDarkFilename()));
		return "redirect:/login-screen";
	}

	/** Resets every text/toggle setting to its default, leaving any uploaded background images untouched -- those are removed independently via their own Remove buttons. */
	@PostMapping("/login-screen/defaults")
	public String resetToDefaults(HttpServletResponse response) throws IOException {
		if (blockExternalOidc(response)) {
			return null;
		}
		LoginScreenSettings current = settingsStore.load();
		LoginScreenSettings defaults = LoginScreenSettings.defaults();
		settingsStore.save(new LoginScreenSettings(
				defaults.logoEnabled(),
				defaults.title(),
				defaults.slogan(),
				defaults.usernameLabel(),
				defaults.passwordLabel(),
				defaults.loginButtonLabel(),
				defaults.footerEnabled(),
				current.backgroundLightFilename(),
				current.backgroundDarkFilename()));
		return "redirect:/login-screen";
	}

	@PostMapping("/login-screen/background/{mode}")
	public String uploadBackground(@PathVariable String mode, @RequestParam("image") MultipartFile image,
			Model model, HttpServletResponse response) throws IOException {
		if (blockExternalOidc(response) || blockUnknownMode(mode, response)) {
			return null;
		}
		LoginScreenSettings current = settingsStore.load();
		if (image == null || image.isEmpty()) {
			return withError(model, current, "Choose an image file to upload.");
		}
		String previous = "light".equals(mode) ? current.backgroundLightFilename() : current.backgroundDarkFilename();
		String filename;
		try {
			filename = imageStore.save(mode, image, previous);
		} catch (IllegalArgumentException e) {
			return withError(model, current, "Unsupported image type. Use PNG, JPEG, GIF, or WEBP.");
		}
		settingsStore.save(withBackground(current, mode, filename));
		return "redirect:/login-screen";
	}

	@PostMapping("/login-screen/background/{mode}/remove")
	public String removeBackground(@PathVariable String mode, HttpServletResponse response) throws IOException {
		if (blockExternalOidc(response) || blockUnknownMode(mode, response)) {
			return null;
		}
		LoginScreenSettings current = settingsStore.load();
		String filename = "light".equals(mode) ? current.backgroundLightFilename() : current.backgroundDarkFilename();
		imageStore.remove(filename);
		settingsStore.save(withBackground(current, mode, null));
		return "redirect:/login-screen";
	}

	/** Streams the currently uploaded background image for this settings page's own preview -- selfieproxy-identity-provider serves the same files to the real login page through its own LoginScreenAssetController, over the same shared /data volume but reached at a different domain. */
	@GetMapping("/login-screen/preview/{mode}")
	public ResponseEntity<byte[]> preview(@PathVariable String mode) throws IOException {
		if (!MODES.contains(mode)) {
			return ResponseEntity.notFound().build();
		}
		LoginScreenSettings current = settingsStore.load();
		String filename = "light".equals(mode) ? current.backgroundLightFilename() : current.backgroundDarkFilename();
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

	private String withError(Model model, LoginScreenSettings settings, String error) {
		model.addAttribute("settings", settings);
		model.addAttribute("errors", List.of(error));
		return "login-screen";
	}

	private LoginScreenSettings withBackground(LoginScreenSettings settings, String mode, String filename) {
		return "light".equals(mode)
				? new LoginScreenSettings(settings.logoEnabled(), settings.title(), settings.slogan(),
						settings.usernameLabel(), settings.passwordLabel(), settings.loginButtonLabel(),
						settings.footerEnabled(), filename, settings.backgroundDarkFilename())
				: new LoginScreenSettings(settings.logoEnabled(), settings.title(), settings.slogan(),
						settings.usernameLabel(), settings.passwordLabel(), settings.loginButtonLabel(),
						settings.footerEnabled(), settings.backgroundLightFilename(), filename);
	}

	private String blankToDefault(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value;
	}

	private boolean blockExternalOidc(HttpServletResponse response) throws IOException {
		if (oidcProperties.isExternal()) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return true;
		}
		return false;
	}

	private boolean blockUnknownMode(String mode, HttpServletResponse response) throws IOException {
		if (!MODES.contains(mode)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return true;
		}
		return false;
	}
}
