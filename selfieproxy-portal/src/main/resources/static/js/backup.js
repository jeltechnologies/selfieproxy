(function () {
	"use strict";

	// Backup page: the download link's filename should reflect the browser's
	// local time, not the server's -- append the IANA zone as a query param
	// and let the server format "now" in that zone (falling back to UTC if
	// missing/invalid).
	var downloadLink = document.getElementById("download-backup-link");
	if (downloadLink) {
		try {
			var zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
			if (zone) {
				var url = new URL(downloadLink.href, window.location.origin);
				url.searchParams.set("tz", zone);
				downloadLink.href = url.toString();
			}
		} catch (e) {
			// Intl unsupported/unavailable -- leave the link as-is, server defaults to UTC.
		}
	}

	// Restore picker: checking a Homelab checks all its nested Exposed Apps;
	// unchecking an Exposed App does not affect its Homelab's checkbox.
	document.querySelectorAll(".restore-homelab").forEach(function (homelabCheckbox) {
		homelabCheckbox.addEventListener("change", function () {
			var tree = homelabCheckbox.closest(".restore-tree");
			if (!tree) {
				return;
			}
			tree.querySelectorAll(".restore-app").forEach(function (appCheckbox) {
				appCheckbox.checked = homelabCheckbox.checked;
			});
		});
	});
})();
