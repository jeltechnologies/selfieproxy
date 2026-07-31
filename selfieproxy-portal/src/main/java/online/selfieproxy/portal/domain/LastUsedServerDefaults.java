package online.selfieproxy.portal.domain;

/** Either field may be null -- domain is left unset for a remote-access (SSH/RDP/VNC) add, which never chooses one. */
public record LastUsedServerDefaults(String domain, String homelabName) {
}
