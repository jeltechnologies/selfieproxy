package online.selfieproxy.remoteconsole.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.GuacamoleSocket;
import org.apache.guacamole.net.GuacamoleTunnel;
import org.apache.guacamole.net.InetGuacamoleSocket;
import org.apache.guacamole.net.SimpleGuacamoleTunnel;
import org.apache.guacamole.protocol.ConfiguredGuacamoleSocket;
import org.apache.guacamole.protocol.GuacamoleConfiguration;

import online.selfieproxy.remoteconsole.config.GuacdProperties;
import online.selfieproxy.remoteconsole.domain.RemoteConsole;
import online.selfieproxy.remoteconsole.domain.RemoteConsoleAuthMode;
import online.selfieproxy.remoteconsole.domain.RemoteConsoleStore;
import online.selfieproxy.remoteconsole.security.RemoteConsoleCredentialCipher;

/**
 * The actual browser <-> guacd bridge: on connect, loads the RemoteConsole
 * record (id comes from ConsoleIdHandshakeInterceptor, stashed into the
 * session attributes since Spring's WebSocketHandler has no built-in path
 * variable binding), decrypts its credential, opens a
 * ConfiguredGuacamoleSocket against selfieproxy-guacd (127.0.0.1:4822, both
 * this service and guacd run network_mode: host -- see root CLAUDE.md), and
 * relays frames in both directions until either side closes. This is the
 * standard guacamole-common server-side bridge pattern (same shape as
 * Guacamole's own reference guacamole-client webapp), built directly on
 * Spring's own WebSocketHandler rather than guacamole-common's JSR-356
 * GuacamoleWebSocketTunnelEndpoint base class, since that class expects
 * container-managed Endpoint registration rather than Spring MVC's handler
 * model.
 */
@Component
public class GuacamoleWebSocketHandler implements WebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(GuacamoleWebSocketHandler.class);
	private static final String TUNNEL_ATTRIBUTE = "guacamoleTunnel";

	private final RemoteConsoleStore remoteConsoleStore;
	private final RemoteConsoleCredentialCipher cipher;
	private final GuacdProperties guacdProperties;

	public GuacamoleWebSocketHandler(RemoteConsoleStore remoteConsoleStore, RemoteConsoleCredentialCipher cipher,
			GuacdProperties guacdProperties) {
		this.remoteConsoleStore = remoteConsoleStore;
		this.cipher = cipher;
		this.guacdProperties = guacdProperties;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		String id = (String) session.getAttributes().get(ConsoleIdHandshakeInterceptor.CONSOLE_ID_ATTRIBUTE);
		RemoteConsole console = id == null ? null : remoteConsoleStore.find(id);
		if (console == null) {
			log.warn("No RemoteConsole record found for id={}", id);
			session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unknown remote console"));
			return;
		}

		try {
			GuacamoleConfiguration config = buildConfiguration(console, session);
			GuacamoleSocket socket = new ConfiguredGuacamoleSocket(
					new InetGuacamoleSocket(guacdProperties.host(), guacdProperties.port()),
					config);
			GuacamoleTunnel tunnel = new SimpleGuacamoleTunnel(socket);
			session.getAttributes().put(TUNNEL_ATTRIBUTE, tunnel);
			startRelayThread(session, tunnel);
		} catch (GuacamoleException e) {
			log.warn("Failed to open guacd connection for remote console {}", id, e);
			session.close(CloseStatus.SERVER_ERROR.withReason("Failed to connect"));
		} catch (RuntimeException e) {
			log.error("Unexpected error opening remote console {}", id, e);
			throw e;
		}
	}

	private void startRelayThread(WebSocketSession session, GuacamoleTunnel tunnel) {
		Thread relay = new Thread(() -> {
			try {
				while (session.isOpen()) {
					char[] message = tunnel.acquireReader().read();
					tunnel.releaseReader();
					if (message == null) {
						break;
					}
					session.sendMessage(new TextMessage(new String(message)));
				}
			} catch (Exception e) {
				log.debug("Guacamole -> browser relay ending for session {}: {}", session.getId(), e.toString());
			} finally {
				closeQuietly(session, tunnel);
			}
		}, "guacamole-relay-" + session.getId());
		relay.setDaemon(true);
		relay.start();
	}

	@Override
	public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
		GuacamoleTunnel tunnel = tunnelOf(session);
		if (tunnel == null || !(message instanceof TextMessage textMessage)) {
			return;
		}
		tunnel.acquireWriter().write(textMessage.getPayload().toCharArray());
		tunnel.releaseWriter();
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		log.debug("WebSocket transport error for session {}: {}", session.getId(), exception.toString());
		closeQuietly(session, tunnelOf(session));
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
		closeQuietly(session, tunnelOf(session));
	}

	@Override
	public boolean supportsPartialMessages() {
		return false;
	}

	private GuacamoleTunnel tunnelOf(WebSocketSession session) {
		return (GuacamoleTunnel) session.getAttributes().get(TUNNEL_ATTRIBUTE);
	}

	private void closeQuietly(WebSocketSession session, GuacamoleTunnel tunnel) {
		if (tunnel != null) {
			try {
				tunnel.close();
			} catch (GuacamoleException e) {
				log.debug("Error closing guacd tunnel: {}", e.toString());
			}
		}
		try {
			if (session.isOpen()) {
				session.close();
			}
		} catch (Exception ignored) {
			// Already closing/closed -- nothing more to do.
		}
	}

	/**
	 * Per-protocol Guacamole parameter names, per Guacamole's own protocol
	 * reference. ignore-cert defaults to the console's own stored preference for
	 * RDP/VNC; SSH has no certificate concept. private-key carries the SSH key's
	 * own text directly (Guacamole's ssh-agent/private-key parameter accepts the
	 * key contents, not a file path).
	 */
	private GuacamoleConfiguration buildConfiguration(RemoteConsole console, WebSocketSession session) {
		GuacamoleConfiguration config = new GuacamoleConfiguration();
		config.setProtocol(console.protocol().name().toLowerCase());
		config.setParameter("hostname", "127.0.0.1");
		config.setParameter("port", String.valueOf(console.tunnelPort()));

		// The browser's initial client.connect("width=...&height=...&dpi=...")
		// call only ever reaches us as this WebSocket's own query string (see
		// ConsoleIdHandshakeInterceptor's javadoc) -- feed it into the
		// configuration so the session starts at a real resolution from the
		// first frame, instead of relying on a later client.sendSize() (eg. a
		// fullscreen toggle) to fix it up after the fact.
		setIfPresent(config, "width", session, ConsoleIdHandshakeInterceptor.DISPLAY_WIDTH_ATTRIBUTE);
		setIfPresent(config, "height", session, ConsoleIdHandshakeInterceptor.DISPLAY_HEIGHT_ATTRIBUTE);
		setIfPresent(config, "dpi", session, ConsoleIdHandshakeInterceptor.DISPLAY_DPI_ATTRIBUTE);

		String secret = cipher.decrypt(console.encryptedSecret());

		switch (console.protocol()) {
			case SSH -> {
				if (console.username() != null) {
					config.setParameter("username", console.username());
				}
				if (console.authMode() == RemoteConsoleAuthMode.PRIVATE_KEY) {
					config.setParameter("private-key", secret);
				} else if (secret != null) {
					config.setParameter("password", secret);
				}
			}
			case RDP -> {
				if (console.username() != null) {
					config.setParameter("username", console.username());
				}
				if (secret != null) {
					config.setParameter("password", secret);
				}
				config.setParameter("ignore-cert", String.valueOf(console.ignoreCertificate()));
				config.setParameter("security", "any");
				// "reconnect" was tried and reverted -- against a real xrdp target it sent
				// guacd's internal FreeRDP client into a repeated disconnect/reconnect storm
				// for ~30s after every single connect (RDPDR channel renegotiation failing
				// each time), independent of whether the browser ever actually resized --
				// the same RDPDR/reconnect fragility tracked upstream as GUACAMOLE-876/900.
				// "display-update" asks the server to resize the existing session in place
				// over RDP's own Display Control channel instead of tearing the connection
				// down, so it doesn't hit that failure mode. Per Guacamole's own docs, a
				// server that doesn't support it (older non-xrdp/non-Windows-8.1+ targets)
				// just silently ignores the resize request rather than erroring -- there is
				// no unsafe fallback case here, only "dynamic resize doesn't happen."
				config.setParameter("resize-method", "display-update");
				// Against this same xrdp target, the RDPGFX graphics pipeline (guacd's
				// AVC420/444/Progressive-codec path) reliably fails to paint anything on
				// first connect -- the cursor (a separate, always-on channel) moves fine,
				// but the desktop framebuffer itself stays solid black until a
				// display-update resize forces guacd to tear down and rebuild the GFX
				// surface, at which point it paints once, then goes blank again on the
				// next resize. Falling back to guacd's classic (pre-GFX) bitmap-update
				// rendering avoids that surface-binding bug entirely -- more bandwidth,
				// but it paints immediately and doesn't depend on a resize to "kick" it.
				config.setParameter("disable-gfx", "true");
			}
			case VNC -> {
				if (console.username() != null) {
					config.setParameter("username", console.username());
				}
				if (secret != null) {
					config.setParameter("password", secret);
				}
			}
		}

		return config;
	}

	private void setIfPresent(GuacamoleConfiguration config, String parameterName, WebSocketSession session,
			String attributeName) {
		Object value = session.getAttributes().get(attributeName);
		if (value != null) {
			config.setParameter(parameterName, value.toString());
		}
	}
}
