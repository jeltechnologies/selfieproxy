(function () {
	"use strict";

	var subdomainInput = document.getElementById("subdomain");
	var domainSelect = document.getElementById("domain");
	var resultInput = document.getElementById("result");

	var hostInput = document.getElementById("host");
	var hostEchoes = document.querySelectorAll(".host-echo");

	var webEnabledCheckbox = document.getElementById("webEnabled");
	var webRowFields = document.getElementById("web-row-fields");
	var webProtocolSelect = document.getElementById("webProtocol");
	var webPortInput = document.getElementById("webPort");

	var terminalEnabledCheckbox = document.getElementById("terminalEnabled");
	var terminalRowFields = document.getElementById("terminal-row-fields");

	var remoteDesktopEnabledCheckbox = document.getElementById("remoteDesktopEnabled");
	var remoteDesktopRowFields = document.getElementById("remote-desktop-row-fields");
	var remoteDesktopProtocolSelect = document.getElementById("remoteDesktopProtocol");
	var remoteDesktopPortInput = document.getElementById("remoteDesktopPort");

	var portForwardingEnabledCheckbox = document.getElementById("portForwardingEnabled");
	var portForwardingRowFields = document.getElementById("port-forwarding-row-fields");

	var removeButton = document.getElementById("remove-button");
	var overlay = document.getElementById("confirm-overlay");
	var confirmRemove = document.getElementById("confirm-remove");
	var cancelRemove = document.getElementById("cancel-remove");
	var deleteForm = document.getElementById("delete-form");

	var serverForm = document.getElementById("server-form");
	var updatingOverlay = document.getElementById("updating-overlay");

	/**
	 * Keeps portInput on its protocol's default port as protocolSelect changes, but only while the
	 * port still matches the *previous* protocol's default -- a port the admin deliberately set (eg.
	 * HTTPS on a custom 12345) must survive a protocol switch untouched. Used independently for the
	 * Web row and the Remote Desktop row.
	 */
	function bindProtocolPortFollow(protocolSelect, portInput, defaultPorts) {
		var touched = false;
		var last = protocolSelect.value;
		portInput.addEventListener("input", function () {
			touched = true;
		});
		protocolSelect.addEventListener("change", function () {
			if (!touched && String(portInput.value) === String(defaultPorts[last])) {
				portInput.value = defaultPorts[protocolSelect.value];
			}
			last = protocolSelect.value;
		});
	}

	bindProtocolPortFollow(webProtocolSelect, webPortInput, {HTTP: 80, HTTPS: 443});
	bindProtocolPortFollow(remoteDesktopProtocolSelect, remoteDesktopPortInput, {RDP: 3389, VNC: 5900});

	function updateVisibility() {
		webRowFields.style.display = webEnabledCheckbox.checked ? "" : "none";
		terminalRowFields.style.display = terminalEnabledCheckbox.checked ? "" : "none";
		remoteDesktopRowFields.style.display = remoteDesktopEnabledCheckbox.checked ? "" : "none";
		portForwardingRowFields.style.display = portForwardingEnabledCheckbox.checked ? "" : "none";
	}

	function updateResult() {
		var domain = domainSelect.value;
		var subdomain = subdomainInput.value;
		resultInput.textContent = subdomain ? subdomain + "." + domain : domain;
	}

	// Every protocol row shows the shared Host field's current value as a plain label between its
	// own Protocol and Homelab server port fields -- one shared input, echoed everywhere it's used.
	function updateHostEchoes() {
		hostEchoes.forEach(function (echo) {
			echo.textContent = hostInput.value;
		});
	}

	hostInput.addEventListener("input", updateHostEchoes);

	webEnabledCheckbox.addEventListener("change", updateVisibility);
	terminalEnabledCheckbox.addEventListener("change", updateVisibility);
	remoteDesktopEnabledCheckbox.addEventListener("change", updateVisibility);
	portForwardingEnabledCheckbox.addEventListener("change", updateVisibility);

	subdomainInput.addEventListener("input", updateResult);
	domainSelect.addEventListener("change", updateResult);

	if (removeButton) {
		removeButton.addEventListener("click", function () {
			overlay.style.display = "flex";
		});
		cancelRemove.addEventListener("click", function () {
			overlay.style.display = "none";
		});
		confirmRemove.addEventListener("click", function () {
			deleteForm.submit();
		});
	}

	// Recreating the tunnel(s) and obtaining a certificate takes a few seconds -- shown only on an
	// actual submission (the native "submit" event only fires once HTML5 validation passes), left
	// up until the browser navigates away on redirect.
	serverForm.addEventListener("submit", function () {
		updatingOverlay.style.display = "flex";
	});

	updateVisibility();
	updateResult();
	updateHostEchoes();
})();
