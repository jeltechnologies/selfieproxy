package online.selfieproxy.portal.domain;

import java.util.Set;

/**
 * Which items in a staged BackupManifest already exist on this server -- computed
 * against live state (Agents, ExposedAppStore, LocalWebsiteStore) by
 * BackupService.diffManifest, and consumed by the restore wizard steps to show a
 * New/Existing status and a contextual warning per item before anything is applied.
 */
public record RestoreDiff(
		Set<String> existingHomelabs,
		Set<String> existingExposedAppFqdns,
		Set<String> existingLocalWebsiteFqdns) {
}
