package online.selfieproxy.remoteconsole.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import online.selfieproxy.remoteconsole.domain.TerminalSettings;
import online.selfieproxy.remoteconsole.domain.TerminalSettingsStore;

/**
 * The SSH console's Settings panel (font size/font family/color theme, see settings.js) reads and
 * writes through here instead of localStorage, so the setting is shared across browsers/devices
 * and can be covered by selfieproxy-portal's configuration export/import. No extra auth here --
 * this whole domain is already SSO-gated at the reverseproxy layer, same as every other route in
 * this module.
 */
@RestController
public class TerminalSettingsController {

	private final TerminalSettingsStore terminalSettingsStore;

	public TerminalSettingsController(TerminalSettingsStore terminalSettingsStore) {
		this.terminalSettingsStore = terminalSettingsStore;
	}

	@GetMapping("/api/terminal-settings")
	public TerminalSettings get() {
		return terminalSettingsStore.load();
	}

	@PostMapping("/api/terminal-settings")
	public TerminalSettings save(@RequestBody TerminalSettings settings) {
		terminalSettingsStore.save(settings);
		return terminalSettingsStore.load();
	}
}
