package online.selfieproxy.portal.domain;

/**
 * The Port Forwarding protocol. Only TCP is wired up today -- UDP support requires a
 * selfieproxy-reverseproxy rewrite that hasn't happened yet, so this enum deliberately has just
 * the one value for now, ready to grow a UDP constant once that lands.
 */
public enum PortForwardingProtocol {
	TCP
}
