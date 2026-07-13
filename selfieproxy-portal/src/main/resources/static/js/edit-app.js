(function () {
	"use strict";

	var domain = (window.selfieProxy && window.selfieProxy.domain) || "";
	var isNew = !!(window.selfieProxy && window.selfieProxy.isNew);
	var ssoUserTouched = false;

	var typeSelect = document.getElementById("type");
	var protocolSelect = document.getElementById("protocol");
	var subdomainInput = document.getElementById("subdomain");
	var nameInput = document.getElementById("name");
	var exposedPortInput = document.getElementById("exposedPort");
	var portInput = document.getElementById("port");
	var resultInput = document.getElementById("result");

	var networkServiceWarning = document.getElementById("network-service-warning");
	var exposedPortField = document.getElementById("exposed-port-field");
	var nameField = document.getElementById("name-field");
	var subdomainField = document.getElementById("subdomain-field");
	var protocolField = document.getElementById("protocol-field");
	var protocolTcpField = document.getElementById("protocol-tcp-field");
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

	// Collapsed until the user clicks "Advanced settings" -- updateVisibility()
	// only ever hides this (when switching away from HTTPS), never shows it.
	advancedSettings.style.display = "none";

	function isNetworkService() {
		return typeSelect.value === "NETWORK_SERVICE";
	}

	function selectedTlsMode() {
		var checked = document.querySelector('input[name="tlsMode"]:checked');
		return checked ? checked.value : null;
	}

	function updateVisibility() {
		var networkService = isNetworkService();

		networkServiceWarning.style.display = networkService ? "" : "none";
		exposedPortField.style.display = networkService ? "" : "none";
		nameField.style.display = networkService ? "" : "none";
		nameInput.required = networkService;
		subdomainField.style.display = networkService ? "none" : "";
		subdomainInput.required = !networkService;

		protocolField.style.display = networkService ? "none" : "";
		protocolTcpField.style.display = networkService ? "" : "none";

		var showHttpsSettings = !networkService && protocolSelect.value === "HTTPS";
		httpsWarning.style.display = (showHttpsSettings && selectedTlsMode() === "BYO_CERT") ? "" : "none";
		advancedToggle.style.display = showHttpsSettings ? "" : "none";
		if (!showHttpsSettings) {
			advancedSettings.style.display = "none";
		}

		// "Server HTTPS" only -- MANAGED is boringproxy's own name for it, and
		// the default TLS mode when nothing else has been chosen yet (mirrors
		// ExposedApp.canProtectWithSso() server-side).
		var canProtectWithSso = showHttpsSettings &&
			(selectedTlsMode() === "MANAGED" || selectedTlsMode() === null);
		ssoProtectedField.style.display = canProtectWithSso ? "" : "none";
		if (!canProtectWithSso) {
			ssoProtectedCheckbox.checked = false;
		} else if (isNew && !ssoUserTouched) {
			ssoProtectedCheckbox.checked = true;
		}
	}

	function updateResult() {
		if (isNetworkService()) {
			var port = exposedPortInput.value;
			resultInput.textContent = domain + (port ? ":" + port : "");
		} else {
			var subdomain = subdomainInput.value || "(subdomain)";
			resultInput.textContent = "https://" + subdomain + "." + domain;
		}
	}

	function refresh() {
		updateVisibility();
		updateResult();
	}

	typeSelect.addEventListener("change", refresh);
	protocolSelect.addEventListener("change", function () {
		portInput.value = protocolSelect.value === "HTTPS" ? 443 : 80;
		refresh();
	});
	subdomainInput.addEventListener("input", refresh);
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
