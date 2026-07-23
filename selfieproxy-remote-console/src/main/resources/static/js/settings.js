(function () {
	"use strict";

	var SETTINGS_URL = "/api/terminal-settings";
	var MIN_FONT_SIZE = 10;
	var MAX_FONT_SIZE = 24;
	var DEFAULT_FONT_SIZE = 15;
	var DEFAULT_THEME_ID = "dark";
	var DEFAULT_FONT_FAMILY_ID = "default";

	// "default" is left byte-for-byte identical to terminal.js's previous
	// hardcoded fontFamily, so anyone who never opens Settings sees no change.
	// Every stack ends in a generic "monospace" fallback for whichever OS the
	// browser is running on.
	var FONTS = [
		{ id: "default", label: "Default", family: "Menlo, Consolas, monospace" },
		{ id: "menlo", label: "Menlo", family: "Menlo, Monaco, monospace" },
		{ id: "consolas", label: "Consolas", family: "Consolas, \"Courier New\", monospace" },
		{ id: "courier-new", label: "Courier New", family: "\"Courier New\", Courier, monospace" },
		{ id: "dejavu-sans-mono", label: "DejaVu Sans Mono", family: "\"DejaVu Sans Mono\", monospace" },
		{ id: "jetbrains-mono", label: "JetBrains Mono", family: "\"JetBrains Mono\", monospace" },
		{ id: "fira-code", label: "Fira Code", family: "\"Fira Code\", monospace" },
		{ id: "cascadia-code", label: "Cascadia Code", family: "\"Cascadia Code\", monospace" },
		{ id: "source-code-pro", label: "Source Code Pro", family: "\"Source Code Pro\", monospace" },
		{ id: "system-monospace", label: "System Monospace", family: "monospace" }
	];

	// Left byte-for-byte identical to terminal.js's previous hardcoded theme, so
	// anyone who never opens Settings sees no visual change.
	var THEMES = [
		{
			id: "dark",
			label: "Dark",
			theme: { background: "#1a1a1a", foreground: "#eee" }
		},
		{
			id: "light",
			label: "Light",
			theme: {
				background: "#ffffff",
				foreground: "#1a1a1a",
				cursor: "#1a1a1a",
				selectionBackground: "#c8d6ff",
				black: "#1a1a1a",
				red: "#c0392b",
				green: "#1e8449",
				yellow: "#a67c00",
				blue: "#1a5fb4",
				magenta: "#8e44ad",
				cyan: "#0e7c86",
				white: "#d0d0d0",
				brightBlack: "#5c5c5c",
				brightRed: "#e74c3c",
				brightGreen: "#27ae60",
				brightYellow: "#d4ac0d",
				brightBlue: "#2980b9",
				brightMagenta: "#af7ac5",
				brightCyan: "#17a2a8",
				brightWhite: "#ffffff"
			}
		},
		{
			// Published Solarized Dark palette (Ethan Schoonover) -- plain hex
			// constants, not pulled from any npm theme package.
			id: "solarized-dark",
			label: "Solarized Dark",
			theme: {
				background: "#002b36",
				foreground: "#839496",
				cursor: "#839496",
				selectionBackground: "#073642",
				black: "#073642",
				red: "#dc322f",
				green: "#859900",
				yellow: "#b58900",
				blue: "#268bd2",
				magenta: "#d33682",
				cyan: "#2aa198",
				white: "#eee8d5",
				brightBlack: "#002b36",
				brightRed: "#cb4b16",
				brightGreen: "#586e75",
				brightYellow: "#657b83",
				brightBlue: "#839496",
				brightMagenta: "#6c71c4",
				brightCyan: "#93a1a1",
				brightWhite: "#fdf6e3"
			}
		},
		{
			// Published Dracula palette (draculatheme.com) -- plain hex constants.
			id: "dracula",
			label: "Dracula",
			theme: {
				background: "#282a36",
				foreground: "#f8f8f2",
				cursor: "#f8f8f2",
				selectionBackground: "#44475a",
				black: "#21222c",
				red: "#ff5555",
				green: "#50fa7b",
				yellow: "#f1fa8c",
				blue: "#bd93f9",
				magenta: "#ff79c6",
				cyan: "#8be9fd",
				white: "#f8f8f2",
				brightBlack: "#6272a4",
				brightRed: "#ff6e6e",
				brightGreen: "#69ff94",
				brightYellow: "#ffffa5",
				brightBlue: "#d6acff",
				brightMagenta: "#ff92df",
				brightCyan: "#a4ffff",
				brightWhite: "#ffffff"
			}
		}
	];

	function clampFontSize(value) {
		var n = Number(value);
		if (!isFinite(n)) {
			return DEFAULT_FONT_SIZE;
		}
		return Math.min(MAX_FONT_SIZE, Math.max(MIN_FONT_SIZE, Math.round(n)));
	}

	function getTheme(themeId) {
		for (var i = 0; i < THEMES.length; i++) {
			if (THEMES[i].id === themeId) {
				return THEMES[i];
			}
		}
		return THEMES[0];
	}

	function getFontFamily(fontFamilyId) {
		for (var i = 0; i < FONTS.length; i++) {
			if (FONTS[i].id === fontFamilyId) {
				return FONTS[i];
			}
		}
		return FONTS[0];
	}

	// Returns a Promise resolving to a settings object -- server-persisted (TerminalSettingsStore/
	// TerminalSettingsController), not localStorage, so it's shared across browsers/devices and
	// covered by selfieproxy-portal's configuration export/import. Never rejects -- any network or
	// parse error falls back to defaults silently, same "don't block the console" spirit the old
	// localStorage try/catch had.
	function load() {
		return fetch(SETTINGS_URL)
			.then(function (response) {
				if (!response.ok) {
					throw new Error("Unexpected status " + response.status);
				}
				return response.json();
			})
			.then(function (parsed) {
				var settings = {
					fontSize: DEFAULT_FONT_SIZE,
					themeId: DEFAULT_THEME_ID,
					fontFamilyId: DEFAULT_FONT_FAMILY_ID
				};
				if (parsed && typeof parsed === "object") {
					if ("fontSize" in parsed) {
						settings.fontSize = clampFontSize(parsed.fontSize);
					}
					if (typeof parsed.themeId === "string") {
						settings.themeId = getTheme(parsed.themeId).id;
					}
					if (typeof parsed.fontFamilyId === "string") {
						settings.fontFamilyId = getFontFamily(parsed.fontFamilyId).id;
					}
				}
				return settings;
			})
			.catch(function () {
				return {
					fontSize: DEFAULT_FONT_SIZE,
					themeId: DEFAULT_THEME_ID,
					fontFamilyId: DEFAULT_FONT_FAMILY_ID
				};
			});
	}

	// Fire-and-forget -- the setting already applies live in this session regardless of whether
	// the save succeeds, so a network error is swallowed silently rather than surfaced to the user.
	function save(settings) {
		fetch(SETTINGS_URL, {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({
				fontSize: settings.fontSize,
				themeId: settings.themeId,
				fontFamilyId: settings.fontFamilyId
			})
		}).catch(function () {
			// Server unreachable -- setting still applies live this session, it just won't persist.
		});
	}

	// settings is the object already resolved from load() -- initPanel no longer fetches itself,
	// since load() is async and the caller (terminal.js) needs the settings before constructing
	// the Terminal anyway.
	function initPanel(terminal, settings, opts) {
		var themeSelect = document.getElementById("theme-select");
		THEMES.forEach(function (t) {
			var option = document.createElement("option");
			option.value = t.id;
			option.textContent = t.label;
			themeSelect.appendChild(option);
		});
		themeSelect.value = settings.themeId;
		themeSelect.addEventListener("change", function () {
			settings.themeId = getTheme(themeSelect.value).id;
			terminal.options.theme = getTheme(settings.themeId).theme;
			save(settings);
		});

		var fontFamilySelect = document.getElementById("font-family-select");
		FONTS.forEach(function (f) {
			var option = document.createElement("option");
			option.value = f.id;
			option.textContent = f.label;
			fontFamilySelect.appendChild(option);
		});
		fontFamilySelect.value = settings.fontFamilyId;
		fontFamilySelect.addEventListener("change", function () {
			settings.fontFamilyId = getFontFamily(fontFamilySelect.value).id;
			terminal.options.fontFamily = getFontFamily(settings.fontFamilyId).family;
			save(settings);
			notifyMetricsChange();
		});

		var fontSizeValue = document.getElementById("font-size-value");
		var decreaseButton = document.getElementById("font-size-decrease");
		var increaseButton = document.getElementById("font-size-increase");

		function renderFontSize() {
			fontSizeValue.textContent = String(settings.fontSize);
		}

		// Both font size AND font family changes alter the pixel size of a
		// terminal character cell, so both need the same re-fit/resize
		// notification back to the caller (see terminal.js's requestFit()).
		function notifyMetricsChange() {
			if (opts && typeof opts.onMetricsChange === "function") {
				opts.onMetricsChange();
			}
		}

		function applyFontSize(newSize) {
			settings.fontSize = clampFontSize(newSize);
			terminal.options.fontSize = settings.fontSize;
			renderFontSize();
			save(settings);
			notifyMetricsChange();
		}

		renderFontSize();
		decreaseButton.addEventListener("click", function () {
			applyFontSize(settings.fontSize - 1);
		});
		increaseButton.addEventListener("click", function () {
			applyFontSize(settings.fontSize + 1);
		});
	}

	window.SelfieProxyTerminalSettings = {
		THEMES: THEMES,
		FONTS: FONTS,
		load: load,
		save: save,
		getTheme: getTheme,
		getFontFamily: getFontFamily,
		initPanel: initPanel
	};
})();
