package online.selfieproxy.portal.web;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.domain.BackupManifest;
import online.selfieproxy.portal.domain.BackupService;
import online.selfieproxy.portal.domain.ExposedApp;
import online.selfieproxy.portal.domain.RestoreResult;
import online.selfieproxy.portal.domain.RestoreSelection;

import jakarta.servlet.http.HttpServletResponse;

/**
 * "Backup & Restore" page: download a single ZIP covering every Homelab,
 * Exposed App ("server"), and Local Website (config + content), and restore
 * from one -- see BackupService for the actual logic and
 * selfieproxy-portal/CLAUDE.md's "Backup and restore" section for the full
 * product behavior.
 */
@Controller
public class BackupController {

	private static final DateTimeFormatter FILENAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");

	private final BackupService backupService;
	private final BoringProxyProperties boringProxyProperties;

	public BackupController(BackupService backupService, BoringProxyProperties boringProxyProperties) {
		this.backupService = backupService;
		this.boringProxyProperties = boringProxyProperties;
	}

	@GetMapping("/backup")
	public String page() {
		return "backup";
	}

	/**
	 * Streams the backup ZIP. GET is fine here despite the "no GET for
	 * state-changing actions" convention elsewhere in this codebase -- creating
	 * a backup reads live state, it doesn't change any. The filename's
	 * timestamp is the browser's local time, not the server's: backup.js reads
	 * Intl.DateTimeFormat().resolvedOptions().timeZone and appends it as
	 * ?tz=..., and tz is validated as a real zone id before use, falling back
	 * to UTC otherwise -- never trusted beyond that parse.
	 */
	@GetMapping("/backup/download")
	public void download(@RequestParam(required = false) String tz, HttpServletResponse response) throws IOException {
		String timestamp = ZonedDateTime.now(resolveZone(tz)).format(FILENAME_TIMESTAMP);
		String safeDomain = boringProxyProperties.domain().replaceAll("[^A-Za-z0-9.-]", "_");
		String filename = "selfieproxy-backup-" + safeDomain + "-" + timestamp + ".zip";
		response.setContentType("application/zip");
		response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
		backupService.writeBackup(response.getOutputStream());
	}

	@PostMapping("/backup/restore/stage")
	public String stage(@RequestParam("backupZip") MultipartFile backupZip, Model model) throws IOException {
		if (backupZip.isEmpty()) {
			model.addAttribute("errors", List.of("Choose a backup ZIP file to upload."));
			return "backup";
		}
		String stagingId;
		try {
			stagingId = backupService.stageRestore(backupZip.getInputStream());
		} catch (IllegalArgumentException e) {
			model.addAttribute("errors", List.of(e.getMessage()));
			return "backup";
		}
		return "redirect:/backup/restore/" + stagingId;
	}

	@GetMapping("/backup/restore/{stagingId}")
	public String picker(@PathVariable String stagingId, Model model) {
		BackupManifest manifest = backupService.readStagedManifest(stagingId);
		Map<String, List<ExposedApp>> appsByHomelab = manifest.exposedApps().stream()
				.collect(Collectors.groupingBy(ExposedApp::homelabName));
		// Normally every app's homelabName is one of manifest.homelabs() already (see
		// BackupService.homelabNames()), but a manually-edited or older backup could reference one
		// that isn't -- surfaced as its own group so it's still selectable, not silently dropped.
		List<ExposedApp> orphanApps = manifest.exposedApps().stream()
				.filter(app -> !manifest.homelabs().contains(app.homelabName()))
				.toList();

		model.addAttribute("stagingId", stagingId);
		model.addAttribute("manifest", manifest);
		model.addAttribute("appsByHomelab", appsByHomelab);
		model.addAttribute("orphanApps", orphanApps);
		return "restore-picker";
	}

	@PostMapping("/backup/restore/{stagingId}/apply")
	public String apply(@PathVariable String stagingId,
			@RequestParam(defaultValue = "false") boolean all,
			@RequestParam(required = false) List<String> homelabs,
			@RequestParam(required = false) List<String> exposedApps,
			@RequestParam(required = false) List<String> localWebsites,
			RedirectAttributes redirectAttributes) {
		BackupManifest manifest = backupService.readStagedManifest(stagingId);
		RestoreSelection selection = all
				? backupService.fullSelection(manifest)
				: new RestoreSelection(nullToEmpty(homelabs), nullToEmpty(exposedApps), nullToEmpty(localWebsites));
		RestoreResult result = backupService.applyRestore(stagingId, selection);
		redirectAttributes.addFlashAttribute("result", result);
		return "redirect:/backup";
	}

	@PostMapping("/backup/restore/{stagingId}/cancel")
	public String cancel(@PathVariable String stagingId) {
		backupService.cancelStaged(stagingId);
		return "redirect:/backup";
	}

	/** An unknown/expired staging id (eg. a stale bookmark, or a double-submit after apply already cleaned it up) lands back on /backup with an explanation instead of a stack trace. */
	@ExceptionHandler(IllegalArgumentException.class)
	public String staleStaging(IllegalArgumentException e, RedirectAttributes redirectAttributes) {
		redirectAttributes.addFlashAttribute("errors", List.of(e.getMessage()));
		return "redirect:/backup";
	}

	private ZoneId resolveZone(String tz) {
		if (tz == null || tz.isBlank()) {
			return ZoneOffset.UTC;
		}
		try {
			return ZoneId.of(tz);
		} catch (DateTimeException e) {
			return ZoneOffset.UTC;
		}
	}

	private List<String> nullToEmpty(List<String> list) {
		return list != null ? list : List.of();
	}
}
