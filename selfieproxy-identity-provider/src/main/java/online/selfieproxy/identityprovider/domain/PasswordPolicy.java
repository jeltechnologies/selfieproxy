package online.selfieproxy.identityprovider.domain;

import java.util.List;

import org.passay.DefaultPasswordValidator;
import org.passay.PasswordData;
import org.passay.PasswordValidator;
import org.passay.ValidationResult;
import org.passay.data.EnglishCharacterData;
import org.passay.resolver.PropertiesMessageResolver;
import org.passay.rule.CharacterRule;
import org.passay.rule.LengthRule;
import org.passay.rule.WhitespaceRule;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Shared password-strength rules, used by ChangePasswordController's forced
 * first-login flow and InternalUsersController's add-user/edit-user handlers --
 * deliberately without a symbol-character requirement (those cause problems
 * on international keyboard layouts).
 */
@Component
public class PasswordPolicy {

	private static final PasswordValidator VALIDATOR = new DefaultPasswordValidator(
			new PropertiesMessageResolver(),
			new LengthRule(12, 128),
			new CharacterRule(EnglishCharacterData.UpperCase, 1),
			new CharacterRule(EnglishCharacterData.LowerCase, 1),
			new CharacterRule(EnglishCharacterData.Digit, 1),
			new WhitespaceRule());

	private final PasswordEncoder passwordEncoder;

	public PasswordPolicy(PasswordEncoder passwordEncoder) {
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * Returns validation error messages for newPassword, or an empty list if
	 * it's acceptable to set. currentPasswordHash may be null (brand-new
	 * user, nothing to differ from yet) to skip the "must differ" check.
	 */
	public List<String> validate(String newPassword, String confirmNewPassword, String currentPasswordHash) {
		if (!newPassword.equals(confirmNewPassword)) {
			return List.of("New password and confirmation do not match.");
		}
		if (currentPasswordHash != null && passwordEncoder.matches(newPassword, currentPasswordHash)) {
			return List.of("New password must be different from the current password.");
		}
		ValidationResult result = VALIDATOR.validate(new PasswordData(newPassword));
		if (!result.isValid()) {
			return result.getMessages();
		}
		return List.of();
	}

	/** Returns validation error messages for newPassword, or an empty list if it's acceptable to set on adminUser. */
	public List<String> validate(String newPassword, String confirmNewPassword, AdminUser adminUser) {
		return validate(newPassword, confirmNewPassword, adminUser.passwordHash());
	}
}
