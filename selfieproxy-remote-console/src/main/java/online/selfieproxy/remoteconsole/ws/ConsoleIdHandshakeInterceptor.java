package online.selfieproxy.remoteconsole.ws;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Spring's WebSocketHandlerRegistry has no built-in path-variable binding
 * (unlike @GetMapping's {id}), so this interceptor pulls the console id out
 * of the upgrade request's own path (/connect/{id}/ws) and stashes it into
 * the session attributes GuacamoleWebSocketHandler reads from.
 */
@Component
public class ConsoleIdHandshakeInterceptor implements HandshakeInterceptor {

	public static final String CONSOLE_ID_ATTRIBUTE = "consoleId";

	private static final Pattern PATH_PATTERN = Pattern.compile("/connect/([^/]+)/ws$");

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
			Map<String, Object> attributes) {
		Matcher matcher = PATH_PATTERN.matcher(request.getURI().getPath());
		if (matcher.find()) {
			attributes.put(CONSOLE_ID_ATTRIBUTE, matcher.group(1));
		}
		return true;
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
			Exception exception) {
		// Nothing to do -- id extraction only matters before the handshake completes.
	}
}
