package online.selfieproxy.portal.portscan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** api.portscan.com's POST /v1/fast response -- queues a scan of the caller's own public IP. */
public record PortScanStartResponseDto(@JsonProperty("eta_seconds") Integer etaSeconds) {
}
