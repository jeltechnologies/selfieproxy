package online.selfieproxy.portal.domain;

import java.util.List;

/**
 * What the user chose to restore from a staged backup, built by
 * BackupController from either the "Restore All" shortcut (server computes
 * the full selection from the staged manifest -- see BackupService) or the
 * checkbox tree the user submitted.
 */
public record RestoreSelection(
		List<String> homelabs,
		List<String> exposedAppSubdomains,
		List<String> localWebsiteDomains) {
}
