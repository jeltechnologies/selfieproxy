package online.selfieproxy.portal.domain;

/** The 4 protocols an Application can simultaneously expose -- each enabled one becomes its own boringproxy tunnel. */
public enum ServerProtocol {
	WEB,
	TERMINAL,
	REMOTE_DESKTOP,
	PORT_FORWARDING
}
