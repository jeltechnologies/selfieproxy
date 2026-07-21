(function () {
	"use strict";

	var protocolSelect = document.getElementById("protocol");
	var portInput = document.getElementById("port");
	var usernameField = document.getElementById("username-field");
	var authModeField = document.getElementById("auth-mode-field");
	var secretLabel = document.getElementById("secret-label");
	var ignoreCertificateField = document.getElementById("ignore-certificate-field");

	var removeButton = document.getElementById("remove-button");
	var overlay = document.getElementById("confirm-overlay");
	var confirmRemove = document.getElementById("confirm-remove");
	var cancelRemove = document.getElementById("cancel-remove");
	var deleteForm = document.getElementById("delete-form");

	var defaultPorts = { SSH: 22, RDP: 3389, VNC: 5900 };
	var portTouched = false;

	function selectedAuthMode() {
		var checked = document.querySelector('input[name="authMode"]:checked');
		return checked ? checked.value : "PASSWORD";
	}

	function refresh() {
		var protocol = protocolSelect.value;
		var isSsh = protocol === "SSH";

		authModeField.style.display = isSsh ? "" : "none";
		ignoreCertificateField.style.display = isSsh ? "none" : "";
		usernameField.style.display = protocol === "VNC" ? "none" : "";

		var isPrivateKey = isSsh && selectedAuthMode() === "PRIVATE_KEY";
		secretLabel.textContent = isPrivateKey ? "Private key" : "Password";
	}

	protocolSelect.addEventListener("change", function () {
		if (!portTouched) {
			portInput.value = defaultPorts[protocolSelect.value];
		}
		refresh();
	});
	portInput.addEventListener("input", function () {
		portTouched = true;
	});
	document.querySelectorAll('input[name="authMode"]').forEach(function (radio) {
		radio.addEventListener("change", refresh);
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
