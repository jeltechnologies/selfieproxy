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
	var httpsWarning = document.getElementById("https-warning");
	var advancedToggle = document.getElementById("advanced-settings-toggle");
	var advancedSettings = document.getElementById("advanced-settings");
	var ssoProtectedField = document.getElementById("sso-protected-field");
	var ssoProtectedCheckbox = document.getElementById("ssoProtected");

	var removeButton = document.getElementById("remove-button");
	var overlay = document.getElementById("confirm-overlay");
	var confirmRemove = document.getElementById("confirm-remove");
	var cancelRemove = document.getElementById("cancel-remove");
	var deleteForm = document.getElementById("delete-form");

	var tlsModeRadios = document.querySelectorAll('input[name="tlsMode"]');

	var defaultModePorts = { SSH: 22, RDP: 3389, VNC: 5900 };
	var portTouched = false;

	// Collapsed until the user clicks "Advanced settings" -- updateVisibility()
	// only ever hides this (when switching away from HTTPS), never shows it.
	advancedSettings.style.display = "none";

	function isNetworkService() {
		return typeSelect.value === "NETWORK_SERVICE";
	}

	function isRemoteAccessMode() {
		return isNetworkService() && modeSelect.value !== "RAW_TCP";
	}

	function selectedTlsMode() {
		var checked = document.querySelector('input[name="tlsMode"]:checked');
		return checked ? checked.value : null;
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

		var showHttpsSettings = !networkService && protocolSelect.value === "HTTPS";
		httpsWarning.style.display = (showHttpsSettings && selectedTlsMode() === "BYO_CERT") ? "" : "none";
		advancedToggle.style.display = showHttpsSettings ? "" : "none";
		if (!showHttpsSettings) {
			advancedSettings.style.display = "none";
		}

		// Plain HTTP is always server-terminated (see TunnelMapper), and HTTPS only under
		// "Server HTTPS" -- MANAGED is boringproxy's own name for it, and the default TLS
		// mode when nothing else has been chosen yet (mirrors ExposedApp.canProtectWithSso()
		// server-side).
		var canProtectWithSso = !networkService && (protocolSelect.value === "HTTP" ||
			(showHttpsSettings && (selectedTlsMode() === "MANAGED" || selectedTlsMode() === null)));
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
		portInput.value = protocolSelect.value === "HTTPS" ? 443 : 80;
		refresh();
	});
	subdomainInput.addEventListener("input", refresh);
	domainSelect.addEventListener("change", updateResult);
	exposedPortInput.addEventListener("input", updateResult);
	tlsModeRadios.forEach(function (radio) {
		radio.addEventListener("change", updateVisibility);
	});
	ssoProtectedCheckbox.addEventListener("change", function () {
		ssoUserTouched = true;
	});

	advancedToggle.addEventListener("click", function () {
		advancedSettings.style.display = advancedSettings.style.display === "none" ? "" : "none";
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

	refresh();
})();
