(function () {
	"use strict";

	var EYE_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path><circle cx="12" cy="12" r="3"></circle></svg>';
	var EYE_OFF_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.94 10.94 0 0 1 12 20c-7 0-11-8-11-8a18.5 18.5 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"></path><line x1="1" y1="1" x2="23" y2="23"></line></svg>';

	var secretInput = document.getElementById("secret");
	var secretToggle = document.getElementById("secret-toggle");
	if (secretInput && secretToggle) {
		secretToggle.addEventListener("click", function () {
			var revealed = secretInput.type === "text";
			secretInput.type = revealed ? "password" : "text";
			secretToggle.innerHTML = revealed ? EYE_ICON : EYE_OFF_ICON;
			var label = revealed ? "Show secret" : "Hide secret";
			secretToggle.title = label;
			secretToggle.setAttribute("aria-label", label);
		});
	}

	var secretCopy = document.getElementById("secret-copy");
	if (secretInput && secretCopy) {
		secretCopy.addEventListener("click", function () {
			navigator.clipboard.writeText(secretInput.value).then(function () {
				var original = secretCopy.title;
				secretCopy.title = "Copied!";
				setTimeout(function () {
					secretCopy.title = original;
				}, 1500);
			});
		});
	}

	var removeButton = document.getElementById("remove-button");
	var overlay = document.getElementById("confirm-overlay");
	var confirmRemove = document.getElementById("confirm-remove");
	var cancelRemove = document.getElementById("cancel-remove");
	var deleteForm = document.getElementById("delete-form");

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

	var connectInfo = document.getElementById("connect-info");
	if (connectInfo) {
		var adminDomain = connectInfo.getAttribute("data-admin-domain");
		var nameInput = document.getElementById("name");
		var dockerRun = document.getElementById("connect-docker-run");
		var dockerCompose = document.getElementById("connect-docker-compose");

		var renderConnectInfo = function () {
			var name = (nameInput.value || "").trim() || "your-homelab-name";

			dockerRun.textContent =
				"docker run -d --name selfieproxy-reverseproxy --restart unless-stopped \\\n" +
				"  --network host \\\n" +
				"  -v selfieproxy-agent-certs:/certs \\\n" +
				"  ghcr.io/jeltechnologies/selfieproxy-reverseproxy:latest agent \\\n" +
				"  -server " + adminDomain + " \\\n" +
				"  -secret \"***your_secret***\" \\\n" +
				"  -agent-name " + name + " \\\n" +
				"  -cert-dir /certs";

			dockerCompose.textContent =
				"services:\n" +
				"  selfieproxy-reverseproxy:\n" +
				"    image: ghcr.io/jeltechnologies/selfieproxy-reverseproxy:latest\n" +
				"    container_name: selfieproxy-reverseproxy\n" +
				"    restart: unless-stopped\n" +
				"    network_mode: host\n" +
				"    volumes:\n" +
				"      - selfieproxy-agent-certs:/certs\n" +
				"    command:\n" +
				"      - agent\n" +
				"      - -server\n" +
				"      - " + adminDomain + "\n" +
				"      - -secret\n" +
				"      - \"***your_secret***\"\n" +
				"      - -agent-name\n" +
				"      - " + name + "\n" +
				"      - -cert-dir\n" +
				"      - /certs\n" +
				"volumes:\n" +
				"  selfieproxy-agent-certs:\n";
		};

		renderConnectInfo();
		nameInput.addEventListener("input", renderConnectInfo);
	}

	var statusRows = document.querySelectorAll("tr[data-agent-name]");
	if (statusRows.length > 0) {
		var refreshStatus = function () {
			fetch("/agents/status", {credentials: "same-origin"})
				.then(function (response) {
					return response.ok ? response.json() : Promise.reject();
				})
				.then(function (agents) {
					agents.forEach(function (agent) {
						var row = document.querySelector('tr[data-agent-name="' + CSS.escape(agent.name) + '"]');
						if (!row) {
							return;
						}
						var dot = row.querySelector(".status-dot");
						var text = row.querySelector(".status-text");
						dot.classList.toggle("status-dot-online", agent.online);
						dot.classList.toggle("status-dot-offline", !agent.online);
						text.textContent = agent.online ? "Connected" : "Disconnected";
						row.querySelector(".app-count").textContent = agent.app_count;
					});
				})
				.catch(function () {
					// Transient network/session hiccup; the next tick retries.
				});
		};

		setInterval(refreshStatus, 2000);
	}
})();
