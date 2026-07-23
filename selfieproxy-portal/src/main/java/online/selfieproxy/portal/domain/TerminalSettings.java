package online.selfieproxy.portal.domain;

/**
 * Mirrored, identical-shape copy of selfieproxy-remote-console's own TerminalSettings (the SSH
 * console's font size/font family/color theme) -- never a shared Java dependency between modules,
 * same precedent as Theme being duplicated between selfieproxy-portal/selfieproxy-identity-provider.
 * Read and, on restore, written by BackupService/TerminalSettingsStore for configuration
 * export/import; selfieproxy-remote-console remains the only app that actually serves/applies it.
 */
public record TerminalSettings(int fontSize, String themeId, String fontFamilyId) {
}
