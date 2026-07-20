package online.selfieproxy.portal.identityprovider.dto;

/** Mirrors identity-provider's internal API error response shape ({@code {"error": "..."}}), same convention as BoringProxyClient's own ApiErrorDto. */
public record ApiErrorDto(String error) {
}
