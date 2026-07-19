(function () {
	"use strict";

	// Backup page: the downloaded ZIP's filename, and its manifest's createdAt, should
	// reflect the browser's local time, not the server's -- fill the hidden tz field with
	// the IANA zone so it's submitted along with the selection (falling back to UTC if
	// missing/invalid).
	var tzField = document.getElementById("backup-tz");
	if (tzField) {
		try {
			var zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
			if (zone) {
				tzField.value = zone;
			}
		} catch (e) {
			// Intl unsupported/unavailable -- leave the field empty, server defaults to UTC.
		}
	}

	// Backup page: Select All / Select None set every checkbox in the Homelabs/Exposed
	// Apps/Local Websites lists at once.
	var selectAllBtn = document.getElementById("select-all-btn");
	var selectNoneBtn = document.getElementById("select-none-btn");
	function setAllChecked(checked) {
		document.querySelectorAll("#backup-form input[type=checkbox]").forEach(function (checkbox) {
			checkbox.checked = checked;
		});
	}
	if (selectAllBtn) {
		selectAllBtn.addEventListener("click", function () { setAllChecked(true); });
	}
	if (selectNoneBtn) {
		selectNoneBtn.addEventListener("click", function () { setAllChecked(false); });
	}
})();
