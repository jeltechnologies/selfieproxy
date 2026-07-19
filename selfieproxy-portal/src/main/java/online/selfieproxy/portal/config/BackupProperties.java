package online.selfieproxy.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where BackupService extracts an uploaded backup ZIP for the restore picker to inspect before anything is applied -- see BackupService.stageRestore. */
@ConfigurationProperties(prefix = "backup")
public record BackupProperties(String restoreStagingPath) {
}
