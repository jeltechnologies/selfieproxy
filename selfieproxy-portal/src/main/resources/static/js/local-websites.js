(function () {
	"use strict";

	var labelInput = document.getElementById("label");
	var domainSelect = document.getElementById("domainSelect");
	var resultInput = document.getElementById("result");

	var removeButton = document.getElementById("remove-button");
	var overlay = document.getElementById("confirm-overlay");
	var confirmRemove = document.getElementById("confirm-remove");
	var cancelRemove = document.getElementById("cancel-remove");
	var deleteForm = document.getElementById("delete-form");

	function updateResult() {
		var label = labelInput.value;
		var domain = domainSelect.value;
		resultInput.textContent = label ? label + "." + domain : domain;
	}

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

	updateResult();
})();
