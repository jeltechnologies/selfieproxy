package online.selfieproxy.identityprovider.internalapi;

import org.apache.catalina.connector.Connector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds a second Tomcat connector on internal-api.port, alongside the normal
 * server.port=8080 connector -- this is what makes InternalUsersController
 * reachable from selfieproxy-portal over the Docker bridge network without
 * ever publishing that port in docker-compose.yaml or wiring it into
 * selfieproxy-reverseproxy's -sso-port. See InternalApiSecurityFilter for the
 * matching request.getLocalPort() guard, without which this second port
 * alone wouldn't actually stop a request that arrived on the main port from
 * reaching the same controller.
 */
@Configuration(proxyBeanMethods = false)
public class TomcatInternalConnectorConfig {

	@Bean
	public WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalConnectorCustomizer(
			@Value("${internal-api.port}") int internalApiPort) {
		return factory -> factory.addAdditionalConnectors(createConnector(internalApiPort));
	}

	private Connector createConnector(int port) {
		Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
		connector.setPort(port);
		connector.setScheme("http");
		return connector;
	}
}
