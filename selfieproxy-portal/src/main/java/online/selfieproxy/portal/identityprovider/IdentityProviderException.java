package online.selfieproxy.portal.identityprovider;

/** Thrown when selfieproxy-identity-provider's internal Users API returns a non-2xx response. */
public class IdentityProviderException extends RuntimeException {

	private final int statusCode;

	public IdentityProviderException(int statusCode, String message) {
		super(message);
		this.statusCode = statusCode;
	}

	public int statusCode() {
		return statusCode;
	}
}
