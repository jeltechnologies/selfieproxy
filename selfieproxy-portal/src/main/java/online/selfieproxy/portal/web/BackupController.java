package online.selfieproxy.portal.web;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import online.selfieproxy.portal.domain.RestoreDiff;
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
		BackupManifest manifest = backupService.readStagedManifest(stagingId);
		return "redirect:/import-configuration/" + stagingId + "/" + firstStep(manifest);
	}

	/** Wizard step (homelabs): every homelab in the staged manifest, New/Existing against live agents, unchecked until the admin actively picks one. Skipped entirely (see firstStep/nextStep) when the export has no homelabs. */
	@GetMapping("/import-configuration/{stagingId}/homelabs")
	public String homelabsStep(@PathVariable String stagingId, Model model) {
		BackupManifest manifest = backupService.readStagedManifest(stagingId);
		RestoreDiff diff = backupService.diffManifest(manifest);
		model.addAttribute("manifest", manifest);
		model.addAttribute("stagingId", stagingId);
		model.addAttribute("existingHomelabs", diff.existingHomelabs());
		addWizardNav(model, "homelabs", stagingId, manifest, List.of(), List.of());
		return "restore-homelabs";
	}

	/** Wizard step (exposed apps): carries the homelab selection forward as hidden fields; its own exposed apps are unchecked on every render. Skipped when the export has no exposed apps. */
	@GetMapping("/import-configuration/{stagingId}/exposed-apps")
	public String exposedAppsStep(@PathVariable String stagingId,
			@RequestParam(required = false) List<String> homelabs, Model model) {
		BackupManifest manifest = backupService.readStagedManifest(stagingId);
		RestoreDiff diff = backupService.diffManifest(manifest);
		List<String> selectedHomelabs = nullToEmpty(homelabs);
		model.addAttribute("manifest", manifest);
		model.addAttribute("stagingId", stagingId);
		model.addAttribute("selectedHomelabs", selectedHomelabs);
		model.addAttribute("existingExposedApps", diff.existingExposedAppSubdomains());
		addWizardNav(model, "exposed-apps", stagingId, manifest, selectedHomelabs, List.of());
		return "restore-exposed-apps";
	}

	/** Wizard step (local websites): carries homelabs + exposed apps forward; its own local websites are unchecked on every render. Skipped when the export has no local websites. */
	@GetMapping("/import-configuration/{stagingId}/local-websites")
	public String localWebsitesStep(@PathVariable String stagingId,
			@RequestParam(required = false) List<String> homelabs,
			@RequestParam(required = false) List<String> exposedApps, Model model) {
		BackupManifest manifest = backupService.readStagedManifest(stagingId);
		RestoreDiff diff = backupService.diffManifest(manifest);
		List<String> selectedHomelabs = nullToEmpty(homelabs);
		List<String> selectedExposedApps = nullToEmpty(exposedApps);
		model.addAttribute("manifest", manifest);
		model.addAttribute("stagingId", stagingId);
		model.addAttribute("selectedHomelabs", selectedHomelabs);
		model.addAttribute("selectedExposedApps", selectedExposedApps);
		model.addAttribute("existingLocalWebsites", diff.existingLocalWebsiteDomains());
		addWizardNav(model, "local-websites", stagingId, manifest, selectedHomelabs, selectedExposedApps);
		return "restore-local-websites";
	}

	/** Wizard's last step: read-only summary of everything selected across whichever category steps weren't skipped, plus the final "cannot be undone" confirmation before Finish applies it. */
	@GetMapping("/import-configuration/{stagingId}/overview")
	public String overviewStep(@PathVariable String stagingId,
			@RequestParam(required = false) List<String> homelabs,
			@RequestParam(required = false) List<String> exposedApps,
			@RequestParam(required = false) List<String> localWebsites, Model model) {
		BackupManifest manifest = backupService.readStagedManifest(stagingId);
		RestoreDiff diff = backupService.diffManifest(manifest);
		List<String> selectedHomelabs = nullToEmpty(homelabs);
		List<String> selectedExposedApps = nullToEmpty(exposedApps);
		model.addAttribute("manifest", manifest);
		model.addAttribute("stagingId", stagingId);
		model.addAttribute("selectedHomelabs", selectedHomelabs);
		model.addAttribute("selectedExposedApps", selectedExposedApps);
		model.addAttribute("selectedLocalWebsites", nullToEmpty(localWebsites));
		model.addAttribute("existingHomelabs", diff.existingHomelabs());
		model.addAttribute("existingExposedApps", diff.existingExposedAppSubdomains());
		model.addAttribute("existingLocalWebsites", diff.existingLocalWebsiteDomains());
		addWizardNav(model, "overview", stagingId, manifest, selectedHomelabs, selectedExposedApps);
		return "restore-overview";
	}

	@PostMapping("/import-configuration/{stagingId}/apply")
	public String apply(@PathVariable String stagingId,
			@RequestParam(required = false) List<String> homelabs,
			@RequestParam(required = false) List<String> exposedApps,
			@RequestParam(required = false) List<String> localWebsites,
			RedirectAttributes redirectAttributes) {
		RestoreSelection selection = new RestoreSelection(nullToEmpty(homelabs), nullToEmpty(exposedApps), nullToEmpty(localWebsites));
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

	/** The review wizard's category steps, in the fixed order they're always shown in when present at all -- see firstStep/nextStep/previousStep/stepNumber below. */
	private static final List<String> CATEGORY_STEPS = List.of("homelabs", "exposed-apps", "local-websites");

	private boolean stepHasItems(String step, BackupManifest manifest) {
		return switch (step) {
			case "homelabs" -> !manifest.homelabs().isEmpty();
			case "exposed-apps" -> !manifest.exposedApps().isEmpty();
			case "local-websites" -> !manifest.localWebsites().isEmpty();
			default -> throw new IllegalArgumentException(step);
		};
	}

	/** The first non-empty category step, or "overview" if the staged export has nothing in any category -- what stage() redirects to right after upload. */
	private String firstStep(BackupManifest manifest) {
		for (String step : CATEGORY_STEPS) {
			if (stepHasItems(step, manifest)) {
				return step;
			}
		}
		return "overview";
	}

	/** The next non-empty category step after from, or "overview" once none remain -- an empty category is skipped entirely rather than shown with nothing to select. */
	private String nextStep(String from, BackupManifest manifest) {
		for (int i = CATEGORY_STEPS.indexOf(from) + 1; i < CATEGORY_STEPS.size(); i++) {
			if (stepHasItems(CATEGORY_STEPS.get(i), manifest)) {
				return CATEGORY_STEPS.get(i);
			}
		}
		return "overview";
	}

	/** The previous non-empty category step before from, or null if every earlier category was empty -- on the effectively-first category step, the template renders Previous as a submit to the existing cancel-form instead of a link, since going further back means abandoning the current staged file (only one file can be staged at a time) and returning to the upload step. */
	private String previousStep(String from, BackupManifest manifest) {
		int start = "overview".equals(from) ? CATEGORY_STEPS.size() - 1 : CATEGORY_STEPS.indexOf(from) - 1;
		for (int i = start; i >= 0; i--) {
			if (stepHasItems(CATEGORY_STEPS.get(i), manifest)) {
				return CATEGORY_STEPS.get(i);
			}
		}
		return null;
	}

	/** 1-based position of step in the wizard, counting the upload step, every non-empty category step, and the overview step -- what each step's "Step N of ..." label shows. */
	private int stepNumber(String step, BackupManifest manifest) {
		if ("overview".equals(step)) {
			return totalSteps(manifest);
		}
		int number = 2;
		for (String candidate : CATEGORY_STEPS) {
			if (candidate.equals(step)) {
				return number;
			}
			if (stepHasItems(candidate, manifest)) {
				number++;
			}
		}
		throw new IllegalArgumentException(step);
	}

	/** Upload + every non-empty category step + overview -- varies with what's actually in the staged export, per the user's request that the wizard not show steps with nothing to review. */
	private int totalSteps(BackupManifest manifest) {
		long presentCategories = CATEGORY_STEPS.stream().filter(step -> stepHasItems(step, manifest)).count();
		return (int) (2 + presentCategories);
	}

	/** Builds a step's URL, including only the query params that step actually needs to carry forward (homelabs for exposed-apps/local-websites, exposedApps for local-websites only) -- used for Previous links, which unlike the Next/Finish forms have no hidden fields of their own to carry state. */
	private String stepUrl(String stagingId, String step, List<String> homelabs, List<String> exposedApps) {
		StringBuilder url = new StringBuilder("/import-configuration/").append(stagingId).append('/').append(step);
		List<String> params = new ArrayList<>();
		if ("exposed-apps".equals(step) || "local-websites".equals(step)) {
			for (String homelab : homelabs) {
				params.add("homelabs=" + URLEncoder.encode(homelab, StandardCharsets.UTF_8));
			}
		}
		if ("local-websites".equals(step)) {
			for (String exposedApp : exposedApps) {
				params.add("exposedApps=" + URLEncoder.encode(exposedApp, StandardCharsets.UTF_8));
			}
		}
		if (!params.isEmpty()) {
			url.append('?').append(String.join("&", params));
		}
		return url.toString();
	}

	/**
	 * Adds stepNumber/totalSteps (for the "Step N of ..." subtitle), nextUrl (absent on the
	 * overview step, which has Finish instead of Next), and previousUrl (null when nothing earlier
	 * applies, in which case the template hides the Previous button) to model for currentStep.
	 * selectedHomelabs/selectedExposedApps are exactly what currentStep itself received as incoming
	 * query params -- enough to rebuild any earlier step's URL, since a step never needs its own
	 * category's selection to link backward to an earlier step.
	 */
	private void addWizardNav(Model model, String currentStep, String stagingId, BackupManifest manifest,
			List<String> selectedHomelabs, List<String> selectedExposedApps) {
		model.addAttribute("stepNumber", stepNumber(currentStep, manifest));
		model.addAttribute("totalSteps", totalSteps(manifest));
		if (!"overview".equals(currentStep)) {
			model.addAttribute("nextUrl", "/import-configuration/" + stagingId + "/" + nextStep(currentStep, manifest));
		}
		String previous = previousStep(currentStep, manifest);
		model.addAttribute("previousUrl", previous == null ? null : stepUrl(stagingId, previous, selectedHomelabs, selectedExposedApps));
	}
}
