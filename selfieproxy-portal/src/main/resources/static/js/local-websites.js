(function () {
	"use strict";

	var typeSelect = document.getElementById("type");
	var labelInput = document.getElementById("label");
	var domainSelect = document.getElementById("domainSelect");
	var resultInput = document.getElementById("result");

	var redirectToField = document.getElementById("redirect-to-field");
	var websiteZipField = document.getElementById("website-zip-field");

	var redirectSourceExisting = document.getElementById("redirectSourceExisting");
	var redirectSourceCustom = document.getElementById("redirectSourceCustom");
	var redirectToExisting = document.getElementById("redirectToExisting");
	var redirectToCustom = document.getElementById("redirectToCustom");

	var removeButton = document.getElementById("remove-button");
	var overlay = document.getElementById("confirm-overlay");
	var confirmRemove = document.getElementById("confirm-remove");
	var cancelRemove = document.getElementById("cancel-remove");
	var deleteForm = document.getElementById("delete-form");

	function isRedirect() {
		return typeSelect.value === "REDIRECT";
	}

	function updateVisibility() {
		redirectToField.style.display = isRedirect() ? "" : "none";
		websiteZipField.style.display = isRedirect() ? "none" : "";
	}

	// redirectToExisting/redirectSourceExisting are absent whenever there's nothing to pick from
	// (no Web Applications or other Local Websites deployed yet) -- Custom is the only option then.
	// Uses visibility rather than display for the inactive control -- display:none would collapse
	// its row to the height of the bare radio label, so switching source made the other row's
	// radio visibly jump up/down; visibility:hidden keeps the same box reserved (both rows always
	// as tall as their real select/input) without showing it. disabled (unaffected by CSS
	// visibility) is what actually keeps the inactive one out of the form submission.
	function updateRedirectSource() {
		var useExisting = !!(redirectSourceExisting && redirectSourceExisting.checked);
		if (redirectToExisting) {
			redirectToExisting.style.visibility = useExisting ? "visible" : "hidden";
			redirectToExisting.disabled = !useExisting;
		}
		redirectToCustom.style.visibility = useExisting ? "hidden" : "visible";
		redirectToCustom.disabled = useExisting;
	}

	function updateResult() {
		var label = labelInput.value;
		var domain = domainSelect.value;
		resultInput.textContent = label ? label + "." + domain : domain;
	}

	typeSelect.addEventListener("change", updateVisibility);
	labelInput.addEventListener("input", updateResult);
	domainSelect.addEventListener("change", updateResult);
	if (redirectSourceExisting) {
		redirectSourceExisting.addEventListener("change", updateRedirectSource);
	}
	if (redirectSourceCustom) {
		redirectSourceCustom.addEventListener("change", updateRedirectSource);
	}

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

	updateVisibility();
	updateRedirectSource();
	updateResult();
})();
