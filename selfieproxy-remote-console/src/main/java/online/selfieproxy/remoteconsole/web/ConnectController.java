package online.selfieproxy.remoteconsole.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import online.selfieproxy.remoteconsole.domain.RemoteConsole;
import online.selfieproxy.remoteconsole.domain.RemoteConsoleStore;

@Controller
public class ConnectController {

	private final RemoteConsoleStore remoteConsoleStore;

	public ConnectController(RemoteConsoleStore remoteConsoleStore) {
		this.remoteConsoleStore = remoteConsoleStore;
	}

	@GetMapping("/connect/{id}")
	public String connect(@PathVariable String id, Model model) {
		RemoteConsole console = remoteConsoleStore.find(id);
		if (console == null) {
			return "console-not-found";
		}
		model.addAttribute("id", id);
		model.addAttribute("console", console);
		return "connect";
	}
}
