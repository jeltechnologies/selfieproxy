package online.selfieproxy.portal.domain;

import java.util.List;
import java.util.Map;

/**
 * What the user chose to restore from a staged backup, built by
 * BackupController from either the "Import All" shortcut (server computes
 * the full selection from the staged manifest -- see BackupService) or the
 * checkbox tree the user submitted.
 *
 * @param domainOverridesByFqdn per-item target domain, keyed by the item's FQDN in the ZIP -- from
 *                               the restore wizard's per-item domain &lt;select&gt;s (see
 *                               BackupController); empty for a plain export-page selection, which
 *                               has no target domain to choose since it reads live state as-is.
 */
public record RestoreSelection(
		List<String> homelabs,
		List<String> exposedAppFqdns,
		List<String> localWebsiteFqdns,
		Map<String, String> domainOverridesByFqdn) {
}
