package online.selfieproxy.portal.boringproxy.dto;

/** Mirrors BoringProxy's CreateTokenRequest REST shape (POST /tokens). "agent" scopes the minted token/secret to one agent name. */
public record CreateTokenRequestDto(String owner, String agent) {
}
