package online.selfieproxy.portal.boringproxy.dto;

/** Mirrors BoringProxy's SetTokenAgent REST shape (PATCH /tokens/{token}). Re-points a token at a different agent name, keeping the token/secret string unchanged. */
public record RenameTokenAgentRequestDto(String agent) {
}
