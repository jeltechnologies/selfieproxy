(function () {
	"use strict";

	var subdomainInput = document.getElementById("subdomain");
	var domainSelect = document.getElementById("domain");
	var resultInput = document.getElementById("result");
	var defaultSubdomainPlaceholder = subdomainInput.placeholder;

	var hostInput = document.getElementById("host");

	var webEnabledCheckbox = document.getElementById("webEnabled");
	var webRowFields = document.getElementById("web-row-fields");
	var webProtocolSelect = document.getElementById("webProtocol");
	var webPortInput = document.getElementById("webPort");

	var terminalEnabledCheckbox = document.getElementById("terminalEnabled");
	var terminalRowFields = document.getElementById("terminal-row-fields");
	var terminalUsernameInput = document.getElementById("terminalUsername");
	var terminalSecretInput = document.getElementById("terminalSecret");

	var remoteDesktopEnabledCheckbox = document.getElementById("remoteDesktopEnabled");
	var remoteDesktopRowFields = document.getElementById("remote-desktop-row-fields");
	var remoteDesktopProtocolSelect = document.getElementById("remoteDesktopProtocol");
	var remoteDesktopPortInput = document.getElementById("remoteDesktopPort");
	var remoteDesktopUsernameInput = document.getElementById("remoteDesktopUsername");
	var remoteDesktopSecretInput = document.getElementById("remoteDesktopSecret");

	var portForwardingEnabledCheckbox = document.getElementById("portForwardingEnabled");
	var portForwardingRowFields = document.getElementById("port-forwarding-row-fields");
	var portForwardingRows = document.getElementById("port-forwarding-rows");
	var MAX_PORT_FORWARDING_ENTRIES = 8;

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

	/**
	 * Terminal and Remote Desktop are usually the same login on the same homelab machine, so typing
	 * into either field mirrors it into its counterpart -- setting .value from script doesn't fire
	 * its own "input" event, so this can't loop.
	 */
	function bindMirrored(a, b) {
		a.addEventListener("input", function () {
			b.value = a.value;
		});
		b.addEventListener("input", function () {
			a.value = b.value;
		});
	}

	bindMirrored(terminalUsernameInput, remoteDesktopUsernameInput);
	bindMirrored(terminalSecretInput, remoteDesktopSecretInput);

	function updateVisibility() {
		webRowFields.style.display = webEnabledCheckbox.checked ? "" : "none";
		terminalRowFields.style.display = terminalEnabledCheckbox.checked ? "" : "none";
		remoteDesktopRowFields.style.display = remoteDesktopEnabledCheckbox.checked ? "" : "none";
		portForwardingRowFields.style.display = portForwardingEnabledCheckbox.checked ? "" : "none";
		updateSubdomainRequirement();
	}

	/**
	 * A blank subdomain (apex) only makes sense for Web -- a whole domain hosting one website.
	 * Terminal/Remote Desktop/Port Forwarding each route over their own separately-generated hidden
	 * tunnel address, never the visible subdomain, so it buys nothing there, and it's the only name
	 * the admin has to find this server again afterward -- require it whenever Web isn't enabled
	 * (mirrors the same rule enforced server-side in ServerController.validate).
	 */
	function updateSubdomainRequirement() {
		var required = !webEnabledCheckbox.checked;
		subdomainInput.toggleAttribute("required", required);
		subdomainInput.placeholder = required ? "Required to identify and edit this server later" : defaultSubdomainPlaceholder;
	}

	function updateResult() {
		var domain = domainSelect.value;
		var subdomain = subdomainInput.value;
		resultInput.textContent = subdomain ? subdomain + "." + domain : domain;
	}

	// Every protocol row shows the shared Host field's current value as a plain label between its
	// own Protocol and Homelab server port fields -- one shared input, echoed everywhere it's used.
	// Queried live (not cached) since Port Forwarding rows can be added after this script first runs.
	function updateHostEchoes() {
		document.querySelectorAll(".host-echo").forEach(function (echo) {
			echo.textContent = hostInput.value;
		});
	}

	/**
	 * Port Forwarding can forward up to 8 individual ports (never a range -- each is its own
	 * boringproxy tunnel), each its own table row of two port fields plus an optional free-text
	 * description (Host and Protocol are shared/fixed, so they're not per-row columns). The last
	 * row is always a blank, editable
	 * ("port-forwarding-entry-blank") row showing "Add" instead of "Remove" -- its inputs are
	 * already named, so typing into it and pressing OK directly (without ever clicking "Add")
	 * still saves it. Clicking "Add" just locks that row read-only (so it can no longer be
	 * accidentally retyped -- Remove + re-add is how you change an already-added port) and appends
	 * a fresh blank row for the next one, unless the cap is now reached. Removing a row never needs
	 * confirmation -- the page's own Cancel link already covers "I didn't mean to change this."
	 */
	function makeBlankPortForwardingRow() {
		var row = document.createElement("tr");
		row.className = "port-forwarding-entry port-forwarding-entry-blank";
		row.innerHTML =
			'<td><input type="number" name="portForwardingTargetPort" min="1" max="65535" step="1"></td>' +
			'<td><input type="number" name="portForwardingPublicPort" min="1024" max="65535" step="1"></td>' +
			'<td><input type="text" name="portForwardingDescription" maxlength="200" placeholder="(optional)"></td>' +
			'<td><button type="button" class="button-small add-port-forwarding-row">Add</button></td>';
		return row;
	}

	function ensureTrailingBlankPortForwardingRow() {
		var hasBlankRow = portForwardingRows.querySelector(".port-forwarding-entry-blank") !== null;
		var count = portForwardingRows.querySelectorAll(".port-forwarding-entry").length;
		if (!hasBlankRow && count < MAX_PORT_FORWARDING_ENTRIES) {
			portForwardingRows.appendChild(makeBlankPortForwardingRow());
		}
	}

	/**
	 * Both ports are required together -- temporarily marking each "required" lets the browser's
	 * own constraint-validation bubble explain exactly what's missing/out of range (an input with
	 * min/max already reports an out-of-range value as invalid without this), then the temporary
	 * attribute is removed again so it doesn't affect a still-blank row on an ordinary submit.
	 */
	function reportPortForwardingRowValidity(inputs) {
		inputs.forEach(function (input) {
			input.setAttribute("required", "required");
		});
		var valid = true;
		for (var i = 0; i < inputs.length && valid; i++) {
			valid = inputs[i].reportValidity();
		}
		inputs.forEach(function (input) {
			input.removeAttribute("required");
		});
		return valid;
	}

	/**
	 * True if some *other* row in the table already has this same value in the same field --
	 * the homelab server port and the port exposed to the internet must each be unique across this
	 * Server's own forwarded ports (server-side has the final say, including against every other
	 * Server on this Selfie Proxy instance for the public port, but this catches the common case
	 * immediately instead of only after a full round trip).
	 */
	function hasDuplicatePortForwardingValue(row, fieldName, value) {
		return Array.prototype.slice.call(portForwardingRows.querySelectorAll(".port-forwarding-entry"))
				.some(function (otherRow) {
					if (otherRow === row) {
						return false;
					}
					var input = otherRow.querySelector('input[name="' + fieldName + '"]');
					return !!input && input.value.trim() !== "" && input.value.trim() === value;
				});
	}

	/** Flags targetInput/publicInput (whichever collides) against every other row already in the table. Returns true (no duplicate) or false (blocked, message shown on the offending field). */
	function reportPortForwardingRowDuplicates(row, targetInput, publicInput) {
		if (hasDuplicatePortForwardingValue(row, "portForwardingTargetPort", targetInput.value.trim())) {
			targetInput.setCustomValidity("This homelab server port is already used by another row above.");
			targetInput.reportValidity();
			targetInput.setCustomValidity("");
			return false;
		}
		if (hasDuplicatePortForwardingValue(row, "portForwardingPublicPort", publicInput.value.trim())) {
			publicInput.setCustomValidity("This port is already used by another row above.");
			publicInput.reportValidity();
			publicInput.setCustomValidity("");
			return false;
		}
		return true;
	}

	portForwardingRows.addEventListener("click", function (event) {
		if (event.target.classList.contains("add-port-forwarding-row")) {
			var row = event.target.closest(".port-forwarding-entry");
			var inputs = Array.prototype.slice.call(row.querySelectorAll("input[type=number]"));
			if (!reportPortForwardingRowValidity(inputs)) {
				return;
			}
			if (!reportPortForwardingRowDuplicates(row, inputs[0], inputs[1])) {
				return;
			}
			inputs.forEach(function (input) {
				input.setAttribute("readonly", "readonly");
			});
			var descriptionInput = row.querySelector('input[name="portForwardingDescription"]');
			if (descriptionInput) {
				descriptionInput.setAttribute("readonly", "readonly");
			}
			row.classList.remove("port-forwarding-entry-blank");
			event.target.textContent = "Remove";
			event.target.classList.remove("add-port-forwarding-row");
			event.target.classList.add("remove-port-forwarding-row");
			ensureTrailingBlankPortForwardingRow();
		} else if (event.target.classList.contains("remove-port-forwarding-row")) {
			event.target.closest(".port-forwarding-entry").remove();
			ensureTrailingBlankPortForwardingRow();
		}
	});

	/**
	 * The trailing blank row can be submitted as-is (see above) -- but if the admin typed into only
	 * one of its two fields and hit OK directly, that's an incomplete entry, not "nothing to add";
	 * block the submit and point at whichever field is still empty. A fully blank trailing row is
	 * fine (nothing to add) and its inputs are un-named first so they don't submit stray empty values.
	 */
	function validatePortForwardingBeforeSubmit(event) {
		var blankRow = portForwardingRows.querySelector(".port-forwarding-entry-blank");
		if (!blankRow) {
			return true;
		}
		var inputs = blankRow.querySelectorAll("input[type=number]");
		var targetInput = inputs[0];
		var publicInput = inputs[1];
		var descriptionInput = blankRow.querySelector('input[name="portForwardingDescription"]');
		var targetFilled = targetInput.value.trim() !== "";
		var publicFilled = publicInput.value.trim() !== "";
		if (!targetFilled && !publicFilled) {
			targetInput.removeAttribute("name");
			publicInput.removeAttribute("name");
			if (descriptionInput) {
				descriptionInput.removeAttribute("name");
			}
			return true;
		}
		if (targetFilled !== publicFilled) {
			event.preventDefault();
			reportPortForwardingRowValidity([targetInput, publicInput]);
			return false;
		}
		if (!reportPortForwardingRowDuplicates(blankRow, targetInput, publicInput)) {
			event.preventDefault();
			return false;
		}
		return true;
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
	serverForm.addEventListener("submit", function (event) {
		if (!validatePortForwardingBeforeSubmit(event)) {
			return;
		}
		updatingOverlay.style.display = "flex";
	});

	updateVisibility();
	updateResult();
	updateHostEchoes();
})();
