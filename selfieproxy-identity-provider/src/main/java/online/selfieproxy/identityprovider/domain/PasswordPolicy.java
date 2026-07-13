package online.selfieproxy.identityprovider.domain;

import java.util.List;

import org.passay.CharacterRule;
import org.passay.EnglishCharacterData;
import org.passay.LengthRule;
import org.passay.PasswordData;
import org.passay.PasswordValidator;
import org.passay.RuleResult;
import org.passay.WhitespaceRule;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Shared password-strength rules, used by both ChangePasswordController's
 * forced first-login flow and AccountController's self-service change --
 * deliberately without a symbol-character requirement (those cause problems
 * on international keyboard layouts).
 */
@Component
public class PasswordPolicy {

	private static final PasswordValidator VALIDATOR = new PasswordValidator(
			new LengthRule(12, 128),
			new CharacterRule(EnglishCharacterData.UpperCase, 1),
			new CharacterRule(EnglishCharacterData.LowerCase, 1),
			new CharacterRule(EnglishCharacterData.Digit, 1),
			new WhitespaceRule());

	private final PasswordEncoder passwordEncoder;

	public PasswordPolicy(PasswordEncoder passwordEncoder) {
		this.passwordEncoder = passwordEncoder;
	}

	/** Returns validation error messages for newPassword, or an empty list if it's acceptable to set on adminUser. */
	public List<String> validate(String newPassword, String confirmNewPassword, AdminUser adminUser) {
		if (!newPassword.equals(confirmNewPassword)) {
			return List.of("New password and confirmation do not match.");
		}
		if (passwordEncoder.matches(newPassword, adminUser.passwordHash())) {
			return List.of("New password must be different from the current password.");
		}
		RuleResult result = VALIDATOR.validate(new PasswordData(newPassword));
		if (!result.isValid()) {
			return VALIDATOR.getMessages(result);
		}
		return List.of();
	}
}
