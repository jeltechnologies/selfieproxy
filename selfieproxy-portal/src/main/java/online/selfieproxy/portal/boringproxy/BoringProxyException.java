package online.selfieproxy.portal.boringproxy;

/** Thrown when BoringProxy's REST API returns a non-2xx response. */
public class BoringProxyException extends RuntimeException {

	private final int statusCode;

	public BoringProxyException(int statusCode, String message) {
		super(message);
		this.statusCode = statusCode;
	}

	public int statusCode() {
		return statusCode;
	}
}
