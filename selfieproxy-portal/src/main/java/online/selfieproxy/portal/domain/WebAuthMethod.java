package online.selfieproxy.portal.domain;

/**
 * How a Server's Web protocol is protected -- an exclusive four-way choice, rendered as the
 * "Authentication methods" radio group on the edit-server page. Exclusive by design rather than by
 * accident: boringproxy checks the single sign on gate before it ever reaches a tunnel's own
 * credential gate (see boringproxy.go's dispatch vs http_proxy.go's checkTunnelAuth), so a tunnel
 * carrying both would silently only ever honour {@link #SSO}.
 *
 * <p>{@link #BASIC} and {@link #TOKEN} exist because {@link #SSO} answers an unauthenticated
 * request with a 302 to the identity provider, which a browser follows and an API client cannot --
 * so a REST service used to be forced to choose between being unusable by its own clients and
 * being completely open.
 */
public enum WebAuthMethod {

	/** Redirect to the Selfie Proxy login. The default for a new Server, and the only browser-shaped option. */
	SSO,

	/** HTTP Basic. Understood by Camunda's REST and MCP Remote Client connectors, but not by its AI Agent connector. */
	BASIC,

	/** A static shared secret in a header, by default {@code Authorization: Bearer <token>}. The only method every Camunda connector accepts. */
	TOKEN,

	/** Open to the internet. */
	NONE
}
