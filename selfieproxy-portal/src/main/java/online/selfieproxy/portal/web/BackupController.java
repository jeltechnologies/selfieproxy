package online.selfieproxy.portal.web;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

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
import online.selfieproxy.portal.domain.RestoreResult;
import online.selfieproxy.portal.domain.RestoreSelection;

import jakarta.servlet.http.HttpServletResponse;

/**
 * "Export configuration" (<code>/export-configuration</code>) and "Import configuration"
 * (<code>/import-configuration</code>) pages, reached from the Settings dropdown --
 * download a ZIP covering a chosen subset of Homelabs, Exposed Apps ("servers"), and
 * Local Websites (config + content), and import from one. The underlying domain types
 * keep the shorter "backup"/"restore" naming -- see BackupService for the actual logic
 * and selfieproxy-portal/CLAUDE.md's "Backup and restore" section for the full product
 * behavior.
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

	/** Export configuration page: flat Homelabs/Exposed Apps/Local Websites checkbox lists over live server state, everything pre-checked, submitting a GET to /export-configuration/download. */
	@GetMapping("/export-configuration")
	public String page(Model model) {
		model.addAttribute("manifest", backupService.buildManifest(ZoneOffset.UTC));
		return "backup";
	}

	/**
	 * Streams the configuration export ZIP, filtered down to the homelabs/exposedApps/localWebsites
	 * the export page's checkboxes selected. GET is fine here despite the "no GET for
	 * state-changing actions" convention elsewhere in this codebase -- creating an export
	 * reads live state, it doesn't change any. The filename's timestamp, and the manifest's
	 * own createdAt field, use the browser's local time, not the server's: backup.js reads
	 * Intl.DateTimeFormat().resolvedOptions().timeZone and sets it on a hidden tz form field,
	 * and tz is validated as a real zone id before use, falling back to UTC otherwise -- never
	 * trusted beyond that parse.
	 */
	@GetMapping("/export-configuration/download")
	public void download(@RequestParam(required = false) String tz,
			@RequestParam(required = false) List<String> homelabs,
			@RequestParam(required = false) List<String> exposedApps,
			@RequestParam(required = false) List<String> localWebsites,
			HttpServletResponse response) throws IOException {
		ZoneId zone = resolveZone(tz);
		String timestamp = ZonedDateTime.now(zone).format(FILENAME_TIMESTAMP);
		String safeDomain = boringProxyProperties.domain().replaceAll("[^A-Za-z0-9.-]", "_");
		String filename = "selfieproxy-config-export-" + safeDomain + "-" + timestamp + ".zip";
		response.setContentType("application/zip");
		response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
		RestoreSelection selection = new RestoreSelection(nullToEmpty(homelabs), nullToEmpty(exposedApps), nullToEmpty(localWebsites));
		backupService.writeBackup(response.getOutputStream(), zone, selection);
	}

	/** Import configuration page: upload form, plus any errors/result flashed back from the staging/apply flow below. */
	@GetMapping("/import-configuration")
	public String restorePage() {
		return "restore";
	}

	@PostMapping("/import-configuration/stage")
	public String stage(@RequestParam("backupZip") MultipartFile backupZip, Model model) throws IOException {
		if (backupZip.isEmpty()) {
			model.addAttribute("errors", List.of("Choose a configuration export ZIP file to upload."));
			return "restore";
		}
		String stagingId;
		try {
			stagingId = backupService.stageRestore(backupZip.getInputStream());
		} catch (IllegalArgumentException e) {
			model.addAttribute("errors", List.of(e.getMessage()));
			return "restore";
		}
		return "redirect:/import-configuration/" + stagingId;
	}

	@GetMapping("/import-configuration/{stagingId}")
	public String picker(@PathVariable String stagingId, Model model) {
		model.addAttribute("manifest", backupService.readStagedManifest(stagingId));
		model.addAttribute("stagingId", stagingId);
		return "restore-picker";
	}

	@PostMapping("/import-configuration/{stagingId}/apply")
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
		return "redirect:/import-configuration";
	}

	@PostMapping("/import-configuration/{stagingId}/cancel")
	public String cancel(@PathVariable String stagingId) {
		backupService.cancelStaged(stagingId);
		return "redirect:/import-configuration";
	}

	/** An unknown/expired staging id (eg. a stale bookmark, or a double-submit after apply already cleaned it up) lands back on /import-configuration with an explanation instead of a stack trace. */
	@ExceptionHandler(IllegalArgumentException.class)
	public String staleStaging(IllegalArgumentException e, RedirectAttributes redirectAttributes) {
		redirectAttributes.addFlashAttribute("errors", List.of(e.getMessage()));
		return "redirect:/import-configuration";
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
