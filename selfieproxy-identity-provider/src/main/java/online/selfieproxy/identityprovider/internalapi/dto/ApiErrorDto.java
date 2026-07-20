package online.selfieproxy.identityprovider.internalapi.dto;

/** Transport-level error body (unknown user, admin-protected, bad/missing token) -- same shape selfieproxy-portal's own BoringProxyClient already expects from an internal API. */
public record ApiErrorDto(String error) {
}
