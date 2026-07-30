package online.selfieproxy.portal.domain;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of the DNS + port reachability check that used to run in the standalone
 * check-prerequisites container at deploy time -- see PrerequisitesCheckService.
 */
public record PrerequisitesCheckResult(Instant checkedAt, List<CheckLine> lines) {

	public enum Severity {
		OK, WARN, ERROR
	}

	public record CheckLine(Severity severity, String message) {
	}

	public boolean hasError() {
		return lines.stream().anyMatch(l -> l.severity() == Severity.ERROR);
	}

	public boolean hasWarning() {
		return lines.stream().anyMatch(l -> l.severity() == Severity.WARN);
	}

	/** ERROR/WARN lines only -- the OK lines aren't interesting to show on the dashboard banner. */
	public List<CheckLine> problems() {
		return lines.stream().filter(l -> l.severity() != Severity.OK).toList();
	}
}
