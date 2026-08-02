package online.selfieproxy.portal.domain;

/**
 * An Application's Port Forwarding (raw TCP passthrough) protocol settings -- present only when
 * Port Forwarding is enabled.
 *
 * @param fqdn       the hidden tunnel's own FQDN (a subdomain of the Application's own domain -- unlike
 *                   Terminal/RemoteDesktop this one is genuinely internet-reachable, just never shown to
 *                   the user as a hostname since raw TCP is addressed as domain:port -- see
 *                   HiddenTunnelFqdnAssigner), assigned once and persisted
 * @param protocol   TCP today; UDP reserved for a future selfieproxy-reverseproxy release
 * @param publicPort  "Port exposed to the internet" -- must be outside the reserved 1-1023 range and unique
 *                    across every other Application's own public port
 * @param targetPort  "Port exposed from the server in homelab" -- the homelab-side target port
 * @param description optional, free-text label the admin can attach to this forwarded port (e.g. "Minecraft
 *                    server") -- purely cosmetic, never validated/unique, blank/null when not set
 */
public record PortForwardingConfig(String fqdn, PortForwardingProtocol protocol, int publicPort, int targetPort,
		String description) {
}
