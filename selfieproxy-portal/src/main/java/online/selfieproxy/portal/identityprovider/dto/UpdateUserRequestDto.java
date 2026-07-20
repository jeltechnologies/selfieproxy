package online.selfieproxy.portal.identityprovider.dto;

/** currentPassword is only checked by identity-provider when the target is the admin row; blank newPassword/confirmNewPassword means "leave unchanged". */
public record UpdateUserRequestDto(String newUsername, String currentPassword, String newPassword,
		String confirmNewPassword) {
}
