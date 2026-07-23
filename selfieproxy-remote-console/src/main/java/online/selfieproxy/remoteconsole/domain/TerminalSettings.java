package online.selfieproxy.remoteconsole.domain;

/**
 * The SSH console's font size/font family/color theme, persisted server-side (see
 * TerminalSettingsStore) so a configuration export/import (selfieproxy-portal's BackupService)
 * can cover them too -- previously these lived only in the browser's localStorage. Mirrored,
 * identical-shape record in selfieproxy-portal for that same reason (never a shared Java
 * dependency between modules).
 */
public record TerminalSettings(int fontSize, String themeId, String fontFamilyId) {
}
