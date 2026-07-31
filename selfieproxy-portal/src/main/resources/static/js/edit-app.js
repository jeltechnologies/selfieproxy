(function () {
	"use strict";

	var isNew = !!(window.selfieProxy && window.selfieProxy.isNew);
	var ssoUserTouched = false;
	// null until the first updateVisibility() run, then tracks whether single sign on protection was
	// available on the *previous* run -- so we can tell "just became available" (default it
	// back on) apart from "was already available at page load" (leave the saved/checked
	// state alone, e.g. an existing app someone deliberately left unprotected).
	var ssoWasEligible = null;

	var typeSelect = document.getElementById("type");
	var modeSelect = document.getElementById("mode");
	var protocolSelect = document.getElementById("protocol");
	var subdomainInput = document.getElementById("subdomain");
	var domainSelect = document.getElementById("domain");
	var nameInput = document.getElementById("name");
	var exposedPortInput = document.getElementById("exposedPort");
	var portInput = document.getElementById("port");
	var resultInput = document.getElementById("result");

	var networkServiceWarning = document.getElementById("network-service-warning");
	var modeField = document.getElementById("mode-field");
	var exposedPortField = document.getElementById("exposed-port-field");
	var nameField = document.getElementById("name-field");
	var subdomainField = document.getElementById("subdomain-field");
	var domainField = document.getElementById("domain-field");
	var resultField = document.getElementById("result-field");
	var protocolField = document.getElementById("protocol-field");
	var usernameField = document.getElementById("username-field");
	var secretField = document.getElementById("secret-field");
	var ignoreCertificateField = document.getElementById("ignore-certificate-field");
	var ssoProtectedField = document.getElementById("sso-protected-field");
	var ssoProtectedCheckbox = document.getElementById("ssoProtected");

	var removeButton = document.getElementById("remove-button");
	var overlay = document.getElementById("confirm-overlay");
	var confirmRemove = document.getElementById("confirm-remove");
	var cancelRemove = document.getElementById("cancel-remove");
	var deleteForm = document.getElementById("delete-form");

	var appForm = document.getElementById("app-form");
	var updatingOverlay = document.getElementById("updating-overlay");

	var defaultModePorts = { SSH: 22, RDP: 3389, VNC: 5900 };
	var defaultProtocolPorts = { HTTP: 80, HTTPS: 443 };
	var portTouched = false;
	var lastProtocol = protocolSelect.value;

	function isNetworkService() {
		return typeSelect.value === "NETWORK_SERVICE";
	}

	function isRemoteAccessMode() {
		return isNetworkService() && modeSelect.value !== "RAW_TCP";
	}

	function updateVisibility() {
		var networkService = isNetworkService();
		var remoteAccess = isRemoteAccessMode();

		networkServiceWarning.style.display = networkService ? "" : "none";
		modeField.style.display = networkService ? "" : "none";

		exposedPortField.style.display = networkService && !remoteAccess ? "" : "none";
		domainField.style.display = remoteAccess ? "none" : "";
		resultField.style.display = remoteAccess ? "none" : "";
		nameField.style.display = networkService ? "" : "none";
		nameInput.required = networkService;
		subdomainField.style.display = networkService ? "none" : "";

		protocolField.style.display = networkService ? "none" : "";

		usernameField.style.display = remoteAccess && modeSelect.value !== "VNC" ? "" : "none";
		secretField.style.display = remoteAccess ? "" : "none";
		ignoreCertificateField.style.display = remoteAccess && modeSelect.value !== "SSH" ? "" : "none";

		// Every Web Application is always end-to-end encrypted (server-terminated, see
		// TunnelMapper/ExposedApp.canProtectWithSso()), so single sign on is available for
		// any Web Application regardless of protocol.
		var canProtectWithSso = !networkService;
		ssoProtectedField.style.display = canProtectWithSso ? "" : "none";
		if (!canProtectWithSso) {
			ssoProtectedCheckbox.checked = false;
		} else if (!ssoUserTouched && (isNew || ssoWasEligible === false)) {
			// Default protection back on for a new app, or whenever a protocol/connectivity
			// change just made an existing app eligible again (e.g. switching HTTP -> HTTPS) --
			// unless the user has explicitly toggled the checkbox themselves this session. An
			// app that was already eligible when the page loaded keeps its saved value untouched.
			ssoProtectedCheckbox.checked = true;
		}
		ssoWasEligible = canProtectWithSso;
	}

	function updateResult() {
		var domain = domainSelect.value;
		if (isNetworkService()) {
			var port = exposedPortInput.value;
			resultInput.textContent = domain + (port ? ":" + port : "");
		} else {
			var subdomain = subdomainInput.value;
			resultInput.textContent = "https://" + (subdomain ? subdomain + "." + domain : domain);
		}
	}

	function refresh() {
		updateVisibility();
		updateResult();
	}

	typeSelect.addEventListener("change", refresh);
	modeSelect.addEventListener("change", function () {
		if (!portTouched && defaultModePorts[modeSelect.value]) {
			portInput.value = defaultModePorts[modeSelect.value];
		}
		refresh();
	});
	portInput.addEventListener("input", function () {
		portTouched = true;
	});
	protocolSelect.addEventListener("change", function () {
		// Only follow the protocol's default port when the port was still at the *previous*
		// protocol's default -- a port the user deliberately set (e.g. HTTPS on a custom 12345)
		// must survive a protocol switch untouched.
		if (String(portInput.value) === String(defaultProtocolPorts[lastProtocol])) {
			portInput.value = defaultProtocolPorts[protocolSelect.value];
		}
		lastProtocol = protocolSelect.value;
		refresh();
	});
	subdomainInput.addEventListener("input", refresh);
	domainSelect.addEventListener("change", updateResult);
	exposedPortInput.addEventListener("input", updateResult);
	ssoProtectedCheckbox.addEventListener("change", function () {
		ssoUserTouched = true;
	});

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

	// Recreating the tunnel and obtaining its certificate takes a few seconds -- shown only on an
	// actual submission (the native "submit" event only fires once HTML5 validation passes), left
	// up until the browser navigates away on redirect.
	appForm.addEventListener("submit", function () {
		updatingOverlay.style.display = "flex";
	});

	refresh();
})();
