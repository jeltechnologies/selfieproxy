package online.selfieproxy.portal.domain;

/**
 * An Application's Web protocol settings -- present only when Web is enabled. The underlying
 * tunnel always lives at the Application's own subdomain+domain (see Server.fqdn()); there's
 * no separate hidden FQDN to track here, unlike Terminal/RemoteDesktop/PortForwarding.
 */
public record WebConfig(Protocol protocol, int port, boolean ssoProtected) {
}
