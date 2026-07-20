package online.selfieproxy.identityprovider.internalapi.dto;

public record CreateUserRequestDto(String username, String password, String confirmPassword) {
}
