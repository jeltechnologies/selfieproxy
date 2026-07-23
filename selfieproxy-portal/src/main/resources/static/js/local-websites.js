(function () {
	"use strict";

	var typeSelect = document.getElementById("type");
	var labelInput = document.getElementById("label");
	var domainSelect = document.getElementById("domainSelect");
	var resultInput = document.getElementById("result");

	var redirectToField = document.getElementById("redirect-to-field");
	var websiteZipField = document.getElementById("website-zip-field");

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

	function updateResult() {
		var label = labelInput.value;
		var domain = domainSelect.value;
		resultInput.textContent = label ? label + "." + domain : domain;
	}

	typeSelect.addEventListener("change", updateVisibility);
	labelInput.addEventListener("input", updateResult);
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

	updateVisibility();
	updateResult();
})();
