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

	// Reported display size/DPI -- the server-side connection details
	// (protocol/host/port/credentials) are already fully configured by
	// selfieproxy-remote-console's GuacamoleConfiguration, so this connect()
	// call only carries client-rendering parameters.
	client.connect(
		"width=" + displayContainer.clientWidth +
		"&height=" + displayContainer.clientHeight +
		"&dpi=96"
	);

	window.addEventListener("beforeunload", function () {
		client.disconnect();
	});

	var keyboard = new Guacamole.Keyboard(document);
	keyboard.onkeydown = function (keysym) {
		client.sendKeyEvent(1, keysym);
	};
	keyboard.onkeyup = function (keysym) {
		client.sendKeyEvent(0, keysym);
	};

	var mouse = new Guacamole.Mouse(client.getDisplay().getElement());
	mouse.onmousedown = mouse.onmouseup = mouse.onmousemove = function (state) {
		client.sendMouseState(state);
	};

	disconnectButton.addEventListener("click", function () {
		client.disconnect();
	});

	fullscreenButton.addEventListener("click", function () {
		if (displayContainer.requestFullscreen) {
			displayContainer.requestFullscreen();
		}
	});
})();
