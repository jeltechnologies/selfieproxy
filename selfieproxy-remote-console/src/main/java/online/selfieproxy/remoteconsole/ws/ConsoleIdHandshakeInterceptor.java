package online.selfieproxy.remoteconsole.ws;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Spring's WebSocketHandlerRegistry has no built-in path-variable binding
 * (unlike @GetMapping's {fqdn}), so this interceptor pulls the app's FQDN out
 * of the upgrade request's own path (/connect/{fqdn}/ws) and stashes it into
 * the session attributes GuacamoleWebSocketHandler reads from. Also captures
 * the width/height/dpi query params connect.js's initial client.connect(...)
 * call sends -- Guacamole.WebSocketTunnel only ever appends these to the WS
 * URL's query string, it never becomes part of the actual Guacamole wire
 * protocol on its own, so without reading them here and feeding them into
 * GuacamoleConfiguration before the ConfiguredGuacamoleSocket handshake, the
 * session starts at whatever default resolution guacd/FreeRDP falls back to
 * regardless of the browser's actual window size -- for RDP specifically this
 * rendered as a blank display until the first later client.sendSize() call
 * (eg. from a fullscreen toggle) forced a real resize.
 */
@Component
public class ConsoleIdHandshakeInterceptor implements HandshakeInterceptor {

	public static final String CONSOLE_FQDN_ATTRIBUTE = "consoleFqdn";
	public static final String DISPLAY_WIDTH_ATTRIBUTE = "displayWidth";
	public static final String DISPLAY_HEIGHT_ATTRIBUTE = "displayHeight";
	public static final String DISPLAY_DPI_ATTRIBUTE = "displayDpi";

	private static final Pattern PATH_PATTERN = Pattern.compile("/connect/([^/]+)/ws$");

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
			Map<String, Object> attributes) {
		Matcher matcher = PATH_PATTERN.matcher(request.getURI().getPath());
		if (matcher.find()) {
			attributes.put(CONSOLE_FQDN_ATTRIBUTE, matcher.group(1));
		}

		Map<String, java.util.List<String>> query = UriComponentsBuilder.fromUri(request.getURI())
				.build().getQueryParams();
		putIfPresent(attributes, DISPLAY_WIDTH_ATTRIBUTE, query, "width");
		putIfPresent(attributes, DISPLAY_HEIGHT_ATTRIBUTE, query, "height");
		putIfPresent(attributes, DISPLAY_DPI_ATTRIBUTE, query, "dpi");

		return true;
	}

	private void putIfPresent(Map<String, Object> attributes, String attributeName,
			Map<String, java.util.List<String>> query, String paramName) {
		java.util.List<String> values = query.get(paramName);
		if (values != null && !values.isEmpty()) {
			attributes.put(attributeName, values.get(0));
		}
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
			Exception exception) {
		// Nothing to do -- id extraction only matters before the handshake completes.
	}
}
