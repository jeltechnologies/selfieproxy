package online.selfieproxy.identityprovider.internalapi.dto;

/** currentPassword is only checked when the target is the admin row; blank newPassword/confirmNewPassword means "leave unchanged". */
public record UpdateUserRequestDto(String newUsername, String currentPassword, String newPassword,
		String confirmNewPassword) {
}
