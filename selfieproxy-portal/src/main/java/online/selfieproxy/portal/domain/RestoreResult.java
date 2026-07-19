package online.selfieproxy.portal.domain;

import java.util.List;

/**
 * Summary of a completed restore, shown to the user afterward. A failure on
 * one item never aborts the rest of the restore (see BackupService.applyRestore),
 * so failures is a list of human-readable "item: reason" strings collected
 * along the way, not an exception.
 */
public record RestoreResult(
		int homelabsRestored,
		int exposedAppsRestored,
		int localWebsitesRestored,
		List<String> failures) {

	public boolean hasFailures() {
		return !failures.isEmpty();
	}
}
