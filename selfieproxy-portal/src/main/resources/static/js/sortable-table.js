(function () {
	"use strict";

	document.querySelectorAll("table[data-sortable]").forEach(function (table) {
		var tbody = table.querySelector("tbody");

		table.querySelectorAll("th[data-sort-key]").forEach(function (th) {
			th.addEventListener("click", function () {
				var index = Array.prototype.indexOf.call(th.parentNode.children, th);
				var rows = Array.prototype.slice.call(tbody.querySelectorAll("tr"));
				var ascending = th.getAttribute("data-sort-dir") !== "asc";

				rows.sort(function (a, b) {
					var av = a.children[index].getAttribute("data-sort-value") || a.children[index].textContent.trim();
					var bv = b.children[index].getAttribute("data-sort-value") || b.children[index].textContent.trim();
					return ascending
							? av.localeCompare(bv, undefined, { numeric: true })
							: bv.localeCompare(av, undefined, { numeric: true });
				});

				table.querySelectorAll("th[data-sort-key]").forEach(function (h) {
					h.removeAttribute("data-sort-dir");
					h.classList.remove("sorted-asc", "sorted-desc");
				});
				th.setAttribute("data-sort-dir", ascending ? "asc" : "desc");
				th.classList.add(ascending ? "sorted-asc" : "sorted-desc");

				rows.forEach(function (row) {
					tbody.appendChild(row);
				});
			});
		});
	});

	// A page can have more than one filter dropdown at once (eg. the Servers list's domain and
	// homelab filters) -- a row is shown only when it matches every active one (AND, not OR).
	var filterSelects = Array.prototype.slice.call(document.querySelectorAll(".list-filter select[data-filter-attr]"));
	if (filterSelects.length > 0) {
		var applyFilters = function () {
			document.querySelectorAll("table[data-sortable] tbody tr").forEach(function (row) {
				var visible = filterSelects.every(function (select) {
					var value = row.getAttribute(select.getAttribute("data-filter-attr"));
					return !select.value || value === select.value;
				});
				row.style.display = visible ? "" : "none";
			});
		};

		// The server pre-selects each remembered option (DomainFilterPreferenceStore), but that
		// alone doesn't hide rows -- apply once on load, same as every later change.
		applyFilters();

		filterSelects.forEach(function (select) {
			select.addEventListener("change", function () {
				applyFilters();
				var saveUrl = select.getAttribute("data-save-url");
				if (saveUrl) {
					fetch(saveUrl, {
						method: "POST",
						headers: { "Content-Type": "application/x-www-form-urlencoded" },
						body: select.getAttribute("data-param") + "=" + encodeURIComponent(select.value),
						credentials: "same-origin"
					});
				}
			});
		});
	}
})();
