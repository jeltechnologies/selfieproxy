package online.selfieproxy.identityprovider.internalapi.dto;

/** One row for the portal's Users list/edit pages -- never carries a password hash. */
public record UserSummaryDto(String username, boolean isAdmin) {
}
