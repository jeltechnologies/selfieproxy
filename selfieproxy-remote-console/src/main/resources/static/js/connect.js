(function () {
	"use strict";

	var displayContainer = document.getElementById("display-container");
	var statusEl = document.getElementById("status");
	var disconnectButton = document.getElementById("disconnect-button");
	var fullscreenButton = document.getElementById("fullscreen-button");

	var tunnel = new Guacamole.WebSocketTunnel(window.selfieProxyConsole.wsUrl);
	var client = new Guacamole.Client(tunnel);

	displayContainer.appendChild(client.getDisplay().getElement());

	var STATE_NAMES = {
		0: "Idle",
		1: "Connecting...",
		2: "Waiting...",
		3: "Connected",
		4: "Disconnecting...",
		5: "Disconnected"
	};

	client.onstatechange = function (state) {
		statusEl.textContent = STATE_NAMES[state] || "";
	};

	client.onerror = function (error) {
		statusEl.textContent = "Error: " + error.message;
	};

	tunnel.onerror = function (status) {
		statusEl.textContent = "Connection error";
	};

	// RDP's GFX pipeline (AVC420/AVC444) chroma-subsamples in 2x2 blocks and
	// requires even width/height -- an odd dimension (eg. a browser window
	// whose content-box height happens to land on an odd pixel count) fails
	// to decode into any visible frame at all, rendering as a permanently
	// blank display rather than a visible error. Flooring to the nearest even
	// number costs at most 1px of cropping and avoids that failure mode
	// entirely.
	function evenDown(n) {
		return n - (n % 2);
	}

	// Reported display size/DPI -- the server-side connection details
	// (protocol/host/port/credentials) are already fully configured by
	// selfieproxy-remote-console's GuacamoleConfiguration, so this connect()
	// call only carries client-rendering parameters.
	client.connect(
		"width=" + evenDown(displayContainer.clientWidth) +
		"&height=" + evenDown(displayContainer.clientHeight) +
		"&dpi=96"
	);

	window.addEventListener("beforeunload", function () {
		client.disconnect();
	});

	// Entering/exiting fullscreen (or any ordinary window resize) changes
	// display-container's size, but the session itself (RDP/VNC desktop
	// resolution, or the SSH pty's rows/cols) stays fixed at whatever was
	// requested at connect() time unless we explicitly ask for a resize --
	// otherwise the remote content just stays letterboxed at its original
	// size. sendSize works uniformly across all three protocols. The short
	// delay is because the container's clientWidth/clientHeight haven't
	// always settled to their final values the instant fullscreenchange fires.
	var resizeTimer = null;
	function requestRemoteResize() {
		if (resizeTimer) {
			window.clearTimeout(resizeTimer);
		}
		resizeTimer = window.setTimeout(function () {
			var width = evenDown(displayContainer.clientWidth);
			var height = evenDown(displayContainer.clientHeight);
			// Exiting fullscreen can report a transient 0 (or near-0) clientWidth/
			// clientHeight before the browser finishes reflowing display-container
			// back into the page -- sending that straight through as the new
			// remote resolution blanks the display and, unlike the reconnect-storm
			// bug, nothing afterwards ever asks for a sane size again. A late,
			// correctly-sized "resize" event never follows on its own once
			// fullscreenchange has already fired, so this has to just discard the
			// bogus reading rather than wait for one.
			if (width < 100 || height < 100) {
				return;
			}
			client.sendSize(width, height);
		}, 100);
	}
	window.addEventListener("resize", requestRemoteResize);
	document.addEventListener("fullscreenchange", requestRemoteResize);

	// X11 keysym for the "v" key (lowercase, since Ctrl+V doesn't involve Shift).
	var KEYSYM_V = 0x76;

	var keyboard = new Guacamole.Keyboard(document);
	keyboard.onkeydown = function (keysym) {
		// Let Ctrl+V through as the browser's own native paste shortcut instead
		// of forwarding it as a literal keystroke: returning true here (rather
		// than the implicit undefined/falsy every other key returns) tells
		// Guacamole.Keyboard not to preventDefault() this keydown, which is what
		// was suppressing the browser's paste action entirely -- forwarding it
		// as a keystroke too just showed up as a stray ^V (readline's
		// quoted-insert) in the terminal, since paste itself was never firing.
		if (keyboard.modifiers.ctrl && keysym === KEYSYM_V) {
			return true;
		}
		client.sendKeyEvent(1, keysym);
	};
	keyboard.onkeyup = function (keysym) {
		if (keyboard.modifiers.ctrl && keysym === KEYSYM_V) {
			return true;
		}
		client.sendKeyEvent(0, keysym);
	};

	// Guacamole's display is a <canvas> -- there's no real, editable DOM element
	// for the browser to dispatch a native "paste" event to, so Ctrl+V silently
	// does nothing without this. Guacamole.InputSink is a hidden, always-focused
	// <textarea> that exists purely to give paste/IME events somewhere real to
	// land; it re-focuses itself automatically whenever nothing else useful has
	// focus (see its own keydown listener).
	var inputSink = new Guacamole.InputSink();
	document.body.appendChild(inputSink.getElement());
	inputSink.focus();
	displayContainer.addEventListener("click", function () {
		inputSink.focus();
	});

	var mouse = new Guacamole.Mouse(client.getDisplay().getElement());
	mouse.onmousedown = mouse.onmouseup = mouse.onmousemove = function (state) {
		client.sendMouseState(state);
	};

	// Clipboard: paste into the session (browser -> remote) and copying *out*
	// of it both work uniformly for SSH/RDP/VNC. Copy needs no client-side
	// text-selection layer at all -- guacd's SSH support tracks mouse-drag
	// selection server-side (using the same mouse events forwarded below) and
	// sends the selected text back as a "clipboard" instruction, which
	// client.onclipboard below already receives.
	function sendClipboardText(text) {
		if (!text) {
			return;
		}
		var stream = client.createClipboardStream("text/plain");
		var writer = new Guacamole.StringWriter(stream);
		writer.sendText(text);
		writer.sendEnd();
	}

	document.addEventListener("paste", function (event) {
		var text = (event.clipboardData || window.clipboardData).getData("text/plain");
		sendClipboardText(text);
		event.preventDefault();
	});

	// Remote clipboard changes (RDP/VNC's own OS clipboard sync) -> browser
	// clipboard. SSH has no OS clipboard concept, so this rarely fires there.
	client.onclipboard = function (stream, mimetype) {
		if (!/^text\//.test(mimetype)) {
			return;
		}
		var reader = new Guacamole.StringReader(stream);
		var data = "";
		reader.ontext = function (text) {
			data += text;
		};
		reader.onend = function () {
			if (navigator.clipboard && navigator.clipboard.writeText) {
				navigator.clipboard.writeText(data).catch(function () {
					// Ignore -- likely blocked by browser permissions outside a user gesture.
				});
			}
		};
	};

	disconnectButton.addEventListener("click", function () {
		client.disconnect();
		// Only succeeds if this tab was opened via window.open() from script
		// (see remote-consoles.html's Connect link) -- browsers refuse
		// script-initiated close() on a tab that was opened by an ordinary
		// link/direct navigation.
		window.close();
	});

	fullscreenButton.addEventListener("click", function () {
		if (displayContainer.requestFullscreen) {
			displayContainer.requestFullscreen();
		}
	});
})();
