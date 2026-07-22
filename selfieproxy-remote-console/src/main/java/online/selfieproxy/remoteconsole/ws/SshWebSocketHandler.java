package online.selfieproxy.remoteconsole.ws;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;

import online.selfieproxy.remoteconsole.domain.RemoteConsole;
import online.selfieproxy.remoteconsole.domain.RemoteConsoleProtocol;
import online.selfieproxy.remoteconsole.domain.RemoteConsoleStore;
import online.selfieproxy.remoteconsole.security.RemoteConsoleCredentialCipher;

/**
 * The direct browser <-> SSH bridge for SSH-mode consoles, entirely separate
 * from GuacamoleWebSocketHandler/guacd: unlike RDP/VNC, an SSH session here is
 * terminated as a real SSH client (Apache MINA SSHD) in this same service,
 * dialing the tunnel port directly (127.0.0.1:tunnelPort -- same loopback
 * dial guacd already does for RDP/VNC, see root CLAUDE.md's host-networking
 * rationale), and the terminal itself is rendered client-side by xterm.js
 * (terminal.js) rather than server-side by guacd. Host key verification is
 * accept-any (see SshClientConfig) -- this system already trusts the
 * tunnel/agent implicitly, matching RDP's own ignore-cert/security=any
 * posture.
 *
 * Wire format on this WebSocket (deliberately simpler than Guacamole's own
 * char-based protocol, since there's no existing protocol to speak here):
 * browser -> server TextMessage is raw keystrokes/input (xterm.js's onData
 * already gives a decoded JS string) written straight to the SSH channel's
 * stdin; browser -> server BinaryMessage is a tiny fixed control message,
 * currently only a resize request encoded as the UTF-8 text
 * "resize:<cols>:<rows>" (a full JSON parser felt like overkill for one
 * two-field message); server -> browser is always BinaryMessage (raw SSH
 * stdout/stderr bytes from the PTY channel), so multi-byte UTF-8 sequences
 * split across TCP reads are never corrupted -- xterm.js's write() accepts a
 * Uint8Array directly, no re-encoding needed.
 */
@Component
public class SshWebSocketHandler implements WebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(SshWebSocketHandler.class);
	private static final String CHANNEL_ATTRIBUTE = "sshChannel";
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(10);
	private static final String RESIZE_PREFIX = "resize:";

	private final RemoteConsoleStore remoteConsoleStore;
	private final RemoteConsoleCredentialCipher cipher;
	private final SshClient sshClient;

	public SshWebSocketHandler(RemoteConsoleStore remoteConsoleStore, RemoteConsoleCredentialCipher cipher,
			SshClient sshClient) {
		this.remoteConsoleStore = remoteConsoleStore;
		this.cipher = cipher;
		this.sshClient = sshClient;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		String fqdn = (String) session.getAttributes().get(ConsoleIdHandshakeInterceptor.CONSOLE_FQDN_ATTRIBUTE);
		RemoteConsole console = fqdn == null ? null : remoteConsoleStore.find(fqdn);
		if (console == null || console.mode() != RemoteConsoleProtocol.SSH) {
			log.warn("No SSH-mode application found for fqdn={}", fqdn);
			session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unknown console"));
			return;
		}

		try {
			ChannelShell channel = openShellChannel(console, session);
			session.getAttributes().put(CHANNEL_ATTRIBUTE, channel);
			startRelayThread(session, channel);
		} catch (IOException e) {
			log.warn("Failed to open SSH connection for {}", fqdn, e);
			session.close(CloseStatus.SERVER_ERROR.withReason("Failed to connect"));
		} catch (RuntimeException e) {
			log.error("Unexpected error opening SSH session for {}", fqdn, e);
			throw e;
		}
	}

	private ChannelShell openShellChannel(RemoteConsole console, WebSocketSession session) throws IOException {
		ConnectFuture connectFuture = sshClient.connect(console.username(), "127.0.0.1", console.tunnelPort());
		ClientSession clientSession = connectFuture.verify(CONNECT_TIMEOUT).getClientSession();

		String secret = cipher.decrypt(console.encryptedSecret());
		if (secret != null) {
			clientSession.addPasswordIdentity(secret);
		}
		AuthFuture authFuture = clientSession.auth().verify(AUTH_TIMEOUT);
		if (!authFuture.isSuccess()) {
			clientSession.close(true);
			throw new IOException("SSH authentication failed for " + console.username() + "@" + console.host());
		}

		ChannelShell channel = clientSession.createShellChannel();
		channel.setUsePty(true);
		channel.setPtyType("xterm-256color");
		channel.setPtyColumns(intAttributeOr(session, ConsoleIdHandshakeInterceptor.TERMINAL_COLS_ATTRIBUTE, 80));
		channel.setPtyLines(intAttributeOr(session, ConsoleIdHandshakeInterceptor.TERMINAL_ROWS_ATTRIBUTE, 24));
		channel.open().verify(OPEN_TIMEOUT);
		return channel;
	}

	private int intAttributeOr(WebSocketSession session, String attributeName, int fallback) {
		Object value = session.getAttributes().get(attributeName);
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.toString());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private void startRelayThread(WebSocketSession session, ChannelShell channel) {
		Thread relay = new Thread(() -> {
			try {
				InputStream remoteOut = channel.getInvertedOut();
				byte[] buffer = new byte[8192];
				int n;
				while (session.isOpen() && (n = remoteOut.read(buffer)) != -1) {
					session.sendMessage(new BinaryMessage(Arrays.copyOf(buffer, n)));
				}
			} catch (Exception e) {
				log.debug("SSH -> browser relay ending for session {}: {}", session.getId(), e.toString());
			} finally {
				closeQuietly(session, channel);
			}
		}, "ssh-relay-" + session.getId());
		relay.setDaemon(true);
		relay.start();
	}

	@Override
	public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
		ChannelShell channel = channelOf(session);
		if (channel == null) {
			return;
		}
		if (message instanceof TextMessage textMessage) {
			OutputStream remoteIn = channel.getInvertedIn();
			remoteIn.write(textMessage.getPayload().getBytes(StandardCharsets.UTF_8));
			remoteIn.flush();
		} else if (message instanceof BinaryMessage binaryMessage) {
			handleControlMessage(channel, StandardCharsets.UTF_8.decode(binaryMessage.getPayload()).toString());
		}
	}

	private void handleControlMessage(ChannelShell channel, String text) {
		if (!text.startsWith(RESIZE_PREFIX)) {
			return;
		}
		String[] parts = text.substring(RESIZE_PREFIX.length()).split(":", 2);
		if (parts.length != 2) {
			return;
		}
		try {
			int cols = Integer.parseInt(parts[0]);
			int rows = Integer.parseInt(parts[1]);
			channel.sendWindowChange(cols, rows);
		} catch (NumberFormatException | IOException e) {
			log.debug("Ignoring malformed/failed resize request '{}': {}", text, e.toString());
		}
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		log.debug("WebSocket transport error for session {}: {}", session.getId(), exception.toString());
		closeQuietly(session, channelOf(session));
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
		closeQuietly(session, channelOf(session));
	}

	@Override
	public boolean supportsPartialMessages() {
		return false;
	}

	private ChannelShell channelOf(WebSocketSession session) {
		return (ChannelShell) session.getAttributes().get(CHANNEL_ATTRIBUTE);
	}

	private void closeQuietly(WebSocketSession session, ChannelShell channel) {
		if (channel != null) {
			try {
				ClientSession clientSession = channel.getClientSession();
				channel.close();
				clientSession.close();
			} catch (Exception e) {
				log.debug("Error closing SSH channel/session: {}", e.toString());
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
}
