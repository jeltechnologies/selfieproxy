package online.selfieproxy.portal.domain;

/**
 * The 2 UI appearance modes shared by this portal and selfieproxy-identity-provider (including its
 * login/change-password/logged-out pages) -- see ThemeStore. Default is LIGHT. The Dracula terminal
 * theme selfieproxy-remote-console offers for SSH consoles is unrelated to this enum entirely --
 * that's its own independent xterm.js color theme, never a UI-chrome mode.
 */
public enum Theme {

	LIGHT, DARK;

	/** The lowercase string persisted to theme.json and set as the <html> data-theme attribute. */
	public String id() {
		return name().toLowerCase();
	}

	/** Falls back to LIGHT for null, blank, or unrecognized input -- a theme setting must never block rendering. */
	public static Theme fromId(String id) {
		if (id == null || id.isBlank()) {
			return LIGHT;
		}
		for (Theme theme : values()) {
			if (theme.id().equalsIgnoreCase(id)) {
				return theme;
			}
		}
		return LIGHT;
	}
}
