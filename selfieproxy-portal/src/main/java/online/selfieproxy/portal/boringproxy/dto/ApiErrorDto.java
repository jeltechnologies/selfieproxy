package online.selfieproxy.portal.boringproxy.dto;

/** Mirrors BoringProxy's REST API error response shape ({@code {"error": "..."}}). */
public record ApiErrorDto(String error) {
}
