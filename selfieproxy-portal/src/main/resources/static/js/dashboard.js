(function () {
	"use strict";

	var statusRows = document.querySelectorAll("tr[data-fqdn]");
	if (statusRows.length === 0) {
		return;
	}

	var refreshStatus = function () {
		fetch("/apps/status", {credentials: "same-origin"})
			.then(function (response) {
				return response.ok ? response.json() : Promise.reject();
			})
			.then(function (apps) {
				apps.forEach(function (app) {
					var row = document.querySelector('tr[data-fqdn="' + CSS.escape(app.fqdn) + '"]');
					if (!row) {
						return;
					}
					var statusSpan = row.querySelector(".status");
					var dot = row.querySelector(".status-dot");
					dot.classList.toggle("status-dot-offline", app.offline);
					dot.classList.toggle("status-dot-online", !app.offline);
					if (app.status_message) {
						statusSpan.title = app.status_message;
					} else {
						statusSpan.removeAttribute("title");
					}

					var connectButton = row.querySelector(".connect-button");
					if (connectButton) {
						if (app.offline) {
							connectButton.setAttribute("aria-disabled", "true");
							connectButton.setAttribute("tabindex", "-1");
						} else {
							connectButton.removeAttribute("aria-disabled");
							connectButton.removeAttribute("tabindex");
						}
					}
				});
			})
			.catch(function () {
				// Transient network/session hiccup; the next tick retries.
			});
	};

	setInterval(refreshStatus, 2000);
})();
