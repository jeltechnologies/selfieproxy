(function () {
	"use strict";

	var domain = (window.selfieProxy && window.selfieProxy.domain) || "";

	var domainInput = document.getElementById("domain");
	var domainLabel = document.querySelector('label[for="domain"]');
	var resultInput = document.getElementById("result");
	var ownDomainRadios = document.querySelectorAll('input[name="ownDomain"]');

	var removeButton = document.getElementById("remove-button");
	var overlay = document.getElementById("confirm-overlay");
	var confirmRemove = document.getElementById("confirm-remove");
	var cancelRemove = document.getElementById("cancel-remove");
	var deleteForm = document.getElementById("delete-form");

	function isOwnDomain() {
		var checked = document.querySelector('input[name="ownDomain"]:checked');
		return checked ? checked.value === "true" : false;
	}

	function updateResult() {
		domainLabel.textContent = isOwnDomain() ? "Domain" : "Subdomain";
		domainInput.placeholder = isOwnDomain() ? "www.example.com" : "";
		if (isOwnDomain()) {
			domainInput.setAttribute("pattern", "\\S*");
			domainInput.title = "Domain cannot contain a space";
			resultInput.textContent = domainInput.value || "(domain)";
		} else {
			domainInput.setAttribute("pattern", "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
			domainInput.title = "Only letters, numbers, and hyphens -- cannot start or end with a hyphen";
			var subdomain = domainInput.value || "(subdomain)";
			resultInput.textContent = subdomain + "." + domain;
		}
	}

	domainInput.addEventListener("input", updateResult);
	ownDomainRadios.forEach(function (radio) {
		radio.addEventListener("change", updateResult);
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

	updateResult();
})();
