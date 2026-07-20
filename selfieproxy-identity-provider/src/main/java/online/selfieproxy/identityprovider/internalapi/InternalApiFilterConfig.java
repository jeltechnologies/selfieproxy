package online.selfieproxy.identityprovider.internalapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Scopes InternalApiSecurityFilter to /internal/** only -- everything else (login, OIDC endpoints) is untouched by it. */
@Configuration
public class InternalApiFilterConfig {

	@Bean
	public FilterRegistrationBean<InternalApiSecurityFilter> internalApiSecurityFilter(
			InternalTokenPublisher tokenPublisher, @Value("${internal-api.port}") int internalApiPort) {
		FilterRegistrationBean<InternalApiSecurityFilter> registration = new FilterRegistrationBean<>(
				new InternalApiSecurityFilter(tokenPublisher, internalApiPort));
		registration.addUrlPatterns("/internal/*");
		return registration;
	}
}
