(function () {
	"use strict";

	// The Settings menu (<details class="user-menu">) only has native browser behavior for
	// opening/closing via its <summary> -- clicking anywhere else on the page while it's open
	// leaves it open. Close it on any click outside the menu.
	document.addEventListener("click", function (event) {
		document.querySelectorAll(".user-menu[open]").forEach(function (menu) {
			if (!menu.contains(event.target)) {
				menu.removeAttribute("open");
			}
		});
	});
})();
