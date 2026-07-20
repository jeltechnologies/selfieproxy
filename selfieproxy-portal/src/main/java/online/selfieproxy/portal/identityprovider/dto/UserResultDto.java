package online.selfieproxy.portal.identityprovider.dto;

import java.util.List;

/** Response for create/update -- always 200 from identity-provider; validation failure is a normal outcome (success=false), not an HTTP error. */
public record UserResultDto(boolean success, List<String> errors) {
}
