package online.selfieproxy.identityprovider.domain;

/**
 * The 2 UI appearance modes shared with selfieproxy-portal (see that module's own Theme/ThemeStore
 * -- it owns the setting; this module only reads it). Mirrored here rather than shared, same
 * precedent as every other type these two modules never share Java code for.
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
