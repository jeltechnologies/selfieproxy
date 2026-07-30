package online.selfieproxy.portal.portscan.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** api.portscan.com's GET /v1/fast poll response, once status reaches "complete". */
public record PortScanStatusResponseDto(String status, @JsonProperty("ports_open") List<OpenPortDto> portsOpen) {

	public record OpenPortDto(int port) {
	}
}
