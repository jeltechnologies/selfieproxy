(function () {
	"use strict";

	var statusEl = document.getElementById("status");
	var disconnectButton = document.getElementById("disconnect-button");
	var fullscreenButton = document.getElementById("fullscreen-button");
	var terminalContainer = document.getElementById("terminal-container");

	var terminal = new Terminal({
		cursorBlink: true,
		fontFamily: "Menlo, Consolas, monospace",
		theme: { background: "#1a1a1a", foreground: "#eee" }
	});
	var fitAddon = new FitAddon.FitAddon();
	terminal.loadAddon(fitAddon);
	terminal.open(terminalContainer);
	fitAddon.fit();

	// cols/rows appended as query params so ConsoleIdHandshakeInterceptor can feed
	// them into the initial PTY size before the SSH channel even opens, the same
	// convention connect.js already uses for RDP/VNC's width/height/dpi.
	var ws = new WebSocket(window.selfieProxyConsole.wsUrl
		+ "?cols=" + terminal.cols + "&rows=" + terminal.rows);
	ws.binaryType = "arraybuffer";

	ws.onopen = function () {
		statusEl.textContent = "Connected";
		terminal.focus();
	};
	ws.onclose = function () {
		statusEl.textContent = "Disconnected";
	};
	ws.onerror = function () {
		statusEl.textContent = "Connection error";
	};

	// Always binary from the server -- see SshWebSocketHandler's wire-format
	// comment: raw SSH stdout/stderr bytes, so a multi-byte UTF-8 sequence split
	// across two TCP reads is never corrupted by an intermediate text decode.
	// xterm.js's write() accepts a Uint8Array directly.
	ws.onmessage = function (event) {
		terminal.write(new Uint8Array(event.data));
	};

	// xterm.js's onData already gives a decoded JS string for real keystrokes/
	// paste input -- sent as a text frame, which the server writes straight to
	// the SSH channel's stdin.
	terminal.onData(function (data) {
		if (ws.readyState === WebSocket.OPEN) {
			ws.send(data);
		}
	});

	// Sent as a *binary* frame deliberately, so the server can tell it apart
	// from ordinary text input on the same connection without any in-band
	// sentinel -- see SshWebSocketHandler's wire-format comment.
	function sendResize() {
		if (ws.readyState === WebSocket.OPEN) {
			ws.send(new TextEncoder().encode("resize:" + terminal.cols + ":" + terminal.rows));
		}
	}

	var resizeTimer = null;
	function requestFit() {
		if (resizeTimer) {
			window.clearTimeout(resizeTimer);
		}
		resizeTimer = window.setTimeout(function () {
			fitAddon.fit();
			sendResize();
		}, 100);
	}
	window.addEventListener("resize", requestFit);
	document.addEventListener("fullscreenchange", requestFit);

	window.addEventListener("beforeunload", function () {
		ws.close();
	});

	disconnectButton.addEventListener("click", function () {
		ws.close();
		// Only succeeds if this tab was opened via window.open() from script --
		// see connect.js's own copy of this comment.
		window.close();
	});

	fullscreenButton.addEventListener("click", function () {
		if (terminalContainer.requestFullscreen) {
			terminalContainer.requestFullscreen();
		}
	});
})();
