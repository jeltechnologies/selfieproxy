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

	var domainFilter = document.getElementById("domainFilter");
	if (domainFilter) {
		domainFilter.addEventListener("change", function () {
			document.querySelectorAll("table[data-sortable] tbody tr").forEach(function (row) {
				var domain = row.getAttribute("data-domain");
				row.style.display = (!domainFilter.value || domain === domainFilter.value) ? "" : "none";
			});
		});
	}
})();
