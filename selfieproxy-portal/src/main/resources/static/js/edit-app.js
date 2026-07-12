(function () {
	"use strict";

	var domain = (window.selfieProxy && window.selfieProxy.domain) || "";

	var typeSelect = document.getElementById("type");
	var protocolSelect = document.getElementById("protocol");
	var subdomainInput = document.getElementById("subdomain");
	var subdomainLabel = document.querySelector('label[for="subdomain"]');
	var nameInput = document.getElementById("name");
	var exposedPortInput = document.getElementById("exposedPort");
	var portInput = document.getElementById("port");
	var resultInput = document.getElementById("result");

	var networkServiceWarning = document.getElementById("network-service-warning");
	var exposedPortField = document.getElementById("exposed-port-field");
	var nameField = document.getElementById("name-field");
	var domainModeField = document.getElementById("domain-mode-field");
	var subdomainField = document.getElementById("subdomain-field");
	var protocolField = document.getElementById("protocol-field");
	var protocolTcpField = document.getElementById("protocol-tcp-field");
	var httpsWarning = document.getElementById("https-warning");
	var advancedToggle = document.getElementById("advanced-settings-toggle");
	var advancedSettings = document.getElementById("advanced-settings");
	var ssoProtectedField = document.getElementById("sso-protected-field");
	var ssoProtectedCheckbox = document.getElementById("ssoProtected");

	var ownDomainRadios = document.querySelectorAll('input[name="ownDomain"]');

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

	function isOwnDomain() {
		var checked = document.querySelector('input[name="ownDomain"]:checked');
		return checked ? checked.value === "true" : false;
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
		domainModeField.style.display = networkService ? "none" : "";
		subdomainField.style.display = networkService ? "none" : "";
		subdomainInput.required = !networkService;
		subdomainLabel.textContent = isOwnDomain() ? "Domain" : "Subdomain";
		subdomainInput.placeholder = isOwnDomain() ? "www.example.com" : "";

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
		}
	}

	function updateResult() {
		if (isNetworkService()) {
			var port = exposedPortInput.value;
			resultInput.textContent = domain + (port ? ":" + port : "");
		} else if (isOwnDomain()) {
			resultInput.textContent = "https://" + (subdomainInput.value || "(domain)");
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
	ownDomainRadios.forEach(function (radio) {
		radio.addEventListener("change", refresh);
	});
	tlsModeRadios.forEach(function (radio) {
		radio.addEventListener("change", updateVisibility);
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
