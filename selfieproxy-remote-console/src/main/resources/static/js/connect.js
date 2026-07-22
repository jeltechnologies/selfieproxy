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

	var isRdp = window.selfieProxyConsole.mode === "RDP";

	// RDP/VNC's own OS clipboard sync (cliprdr et al) only updates the remote
	// clipboard's *contents* -- unlike guacd's SSH terminal support, it never
	// auto-inserts that text anywhere. The focused remote application still
	// needs the actual Ctrl+V keystroke delivered to it so its own native
	// Paste command fires and reads back the clipboard guacd just updated.
	// SSH is the opposite: guacd's terminal emulation inserts received
	// clipboard text into the pty directly on its own, so forwarding the
	// literal keystroke there instead shows up as a stray ^V (readline's
	// quoted-insert) -- see the keyboard handlers below.
	var forwardPasteKeystroke = window.selfieProxyConsole.mode !== "SSH";

	// Reported display size/DPI -- the server-side connection details
	// (protocol/host/port/credentials) are already fully configured by
	// selfieproxy-remote-console's GuacamoleConfiguration, so this connect()
	// call only carries client-rendering parameters.
	client.connect(
		"width=" + evenDown(displayContainer.clientWidth) +
		"&height=" + evenDown(displayContainer.clientHeight) +
		"&dpi=96"
	);

	// Live testing narrowed this down further than the CLAUDE.md history below
	// suggests: neither a marginal nor a dramatic resize (of any timing) ever
	// made RDP paint in the normal (non-fullscreen) window, but the Fullscreen
	// API transition always did -- even though both paths call the identical
	// client.sendSize(). A display:none/'' toggle (forcing a full layout-tree
	// removal/re-add) didn't help either. Comparing against Termix-SSH/Termix's
	// own guacd-based RDP viewer (GuacamoleDisplay.tsx) found the actual
	// difference: it never relies on the server matching the window size at
	// all -- it fits the canvas to the container via Guacamole.Display's own
	// scale() method, a CSS *transform*, not a layout change. Browsers handle
	// transform changes very differently from display:none for GPU-composited
	// canvas content (which is exactly what the RDPGFX/AVC decode path
	// produces) -- a transform nudge can force a recomposite that a layout
	// removal doesn't. Nudging the scale away from and back to 1 forces that
	// same recomposite without actually changing the visual size.
	function forceRepaint() {
		var display = client.getDisplay();
		display.scale(0.999999);
		requestAnimationFrame(function () {
			display.scale(1);
		});
	}

	if (isRdp) {
		var kicked = false;
		client.onstatechange = (function (original) {
			return function (state) {
				original(state);
				if (state === 3 && !kicked) {
					kicked = true;
					forceRepaint();
				}
			};
		})(client.onstatechange);
	}

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
			if (isRdp) {
				forceRepaint();
			}
		}, 100);
	}
	window.addEventListener("resize", requestRemoteResize);
	document.addEventListener("fullscreenchange", requestRemoteResize);

	// X11 keysym for the "v" key (lowercase, since Ctrl+V doesn't involve Shift).
	var KEYSYM_V = 0x76;

	var keyboard = new Guacamole.Keyboard(document);
	keyboard.onkeydown = function (keysym) {
		// Always let Ctrl+V through as the browser's own native paste shortcut
		// (never preventDefault it) -- that's what lets the "paste" listener
		// below actually fire and push the clipboard text to guacd. On top of
		// that, RDP/VNC also need the literal keystroke forwarded so the
		// remote's own focused application performs its native paste; SSH
		// does not (see forwardPasteKeystroke above).
		if (keyboard.modifiers.ctrl && keysym === KEYSYM_V) {
			if (forwardPasteKeystroke) {
				client.sendKeyEvent(1, keysym);
			}
			return true;
		}
		client.sendKeyEvent(1, keysym);
	};
	keyboard.onkeyup = function (keysym) {
		if (keyboard.modifiers.ctrl && keysym === KEYSYM_V) {
			if (forwardPasteKeystroke) {
				client.sendKeyEvent(0, keysym);
			}
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

	// Clipboard: copying *out* of the session works uniformly for SSH/RDP/VNC
	// and needs no client-side text-selection layer at all -- guacd's SSH
	// support tracks mouse-drag selection server-side (using the same mouse
	// events forwarded below) and sends the selected text back as a
	// "clipboard" instruction, which client.onclipboard below already
	// receives. Pasting *in* (browser -> remote) shares this same
	// sendClipboardText/paste-listener plumbing across all three protocols,
	// but RDP/VNC additionally need the literal Ctrl+V keystroke forwarded
	// too -- see forwardPasteKeystroke above.
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
		// (see dashboard.html's Connect link) -- browsers refuse
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
