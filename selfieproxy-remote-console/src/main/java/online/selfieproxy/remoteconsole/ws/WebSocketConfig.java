package online.selfieproxy.remoteconsole.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final GuacamoleWebSocketHandler guacamoleWebSocketHandler;
	private final ConsoleIdHandshakeInterceptor consoleIdHandshakeInterceptor;

	public WebSocketConfig(GuacamoleWebSocketHandler guacamoleWebSocketHandler,
			ConsoleIdHandshakeInterceptor consoleIdHandshakeInterceptor) {
		this.guacamoleWebSocketHandler = guacamoleWebSocketHandler;
		this.consoleIdHandshakeInterceptor = consoleIdHandshakeInterceptor;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// No setAllowedOrigins() call -- Spring's default same-origin-only check is
		// exactly what we want here, never cross-origin.
		registry.addHandler(guacamoleWebSocketHandler, "/connect/*/ws")
				.addInterceptors(consoleIdHandshakeInterceptor);
	}
}
