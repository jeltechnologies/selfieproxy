package online.selfieproxy.portal.identityprovider.dto;

public record CreateUserRequestDto(String username, String password, String confirmPassword) {
}
