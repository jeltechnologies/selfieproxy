(function () {
	"use strict";

	// Import wizard steps (Homelabs/Applications/Local Websites): Select All / Select None
	// set every checkbox in that step's item list at once.
	var selectAllBtn = document.getElementById("select-all-btn");
	var selectNoneBtn = document.getElementById("select-none-btn");
	function setAllChecked(checked) {
		document.querySelectorAll("#wizard-form input[type=checkbox]").forEach(function (checkbox) {
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
