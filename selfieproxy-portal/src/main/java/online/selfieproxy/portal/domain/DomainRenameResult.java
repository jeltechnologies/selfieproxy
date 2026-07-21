package online.selfieproxy.portal.domain;

import java.util.List;

/**
 * Summary of a secondary domain rename cascade (see DomainsController), shown to the admin
 * afterward. A failure on one application/local website never aborts the rest of the rename --
 * failures is a list of human-readable "item: reason" strings collected along the way, the same
 * pattern as RestoreResult.
 */
public record DomainRenameResult(int appsUpdated, int sitesUpdated, List<String> failures) {

	public boolean hasFailures() {
		return !failures.isEmpty();
	}
}
