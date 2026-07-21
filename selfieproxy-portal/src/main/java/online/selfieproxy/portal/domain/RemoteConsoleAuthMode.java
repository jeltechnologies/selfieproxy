package online.selfieproxy.portal.domain;

/** PRIVATE_KEY is only ever offered for {@link RemoteConsoleProtocol#SSH}; RDP/VNC always use PASSWORD. */
public enum RemoteConsoleAuthMode {
	PASSWORD,
	PRIVATE_KEY
}
