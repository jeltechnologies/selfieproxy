package online.selfieproxy.portal.boringproxy.dto;

/** Mirrors BoringProxy's TokenData REST shape (entries of GET /tokens). */
public record TokenDataDto(String owner, String agent) {
}
