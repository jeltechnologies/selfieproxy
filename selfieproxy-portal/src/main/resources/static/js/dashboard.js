(function () {
	"use strict";

	var statusRows = document.querySelectorAll("tr[data-fqdn]");
	if (statusRows.length === 0) {
		return;
	}

	var refreshStatus = function () {
		fetch("/servers/status", {credentials: "same-origin"})
			.then(function (response) {
				return response.ok ? response.json() : Promise.reject();
			})
			.then(function (servers) {
				servers.forEach(function (server) {
					var row = document.querySelector('tr[data-fqdn="' + CSS.escape(server.fqdn) + '"]');
					if (!row) {
						return;
					}
					var statusSpan = row.querySelector(".status");
					var dot = row.querySelector(".status-dot");
					dot.classList.toggle("status-dot-offline", server.offline);
					dot.classList.toggle("status-dot-online", !server.offline);
					if (server.status_message) {
						statusSpan.title = server.status_message;
					} else {
						statusSpan.removeAttribute("title");
					}

					row.querySelectorAll(".connect-button").forEach(function (connectButton) {
						if (server.offline) {
							connectButton.setAttribute("aria-disabled", "true");
							connectButton.setAttribute("tabindex", "-1");
						} else {
							connectButton.removeAttribute("aria-disabled");
							connectButton.removeAttribute("tabindex");
						}
					});
				});
			})
			.catch(function () {
				// Transient network/session hiccup; the next tick retries.
			});
	};

	setInterval(refreshStatus, 2000);
})();
