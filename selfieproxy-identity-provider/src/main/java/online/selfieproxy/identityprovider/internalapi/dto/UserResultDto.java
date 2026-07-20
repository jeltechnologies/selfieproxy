package online.selfieproxy.identityprovider.internalapi.dto;

import java.util.List;

/** Response for create/update -- always 200; validation failure is a normal outcome (success=false), not an HTTP error. */
public record UserResultDto(boolean success, List<String> errors) {

	public static UserResultDto ok() {
		return new UserResultDto(true, List.of());
	}

	public static UserResultDto failure(List<String> errors) {
		return new UserResultDto(false, errors);
	}
}
