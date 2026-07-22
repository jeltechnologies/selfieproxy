package online.selfieproxy.remoteconsole.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import online.selfieproxy.remoteconsole.domain.RemoteConsole;
import online.selfieproxy.remoteconsole.domain.RemoteConsoleProtocol;
import online.selfieproxy.remoteconsole.domain.RemoteConsoleStore;

@Controller
public class ConnectController {

	private final RemoteConsoleStore remoteConsoleStore;

	public ConnectController(RemoteConsoleStore remoteConsoleStore) {
		this.remoteConsoleStore = remoteConsoleStore;
	}

	@GetMapping("/connect/{fqdn}")
	public String connect(@PathVariable String fqdn, Model model) {
		RemoteConsole console = remoteConsoleStore.find(fqdn);
		if (console == null) {
			return "console-not-found";
		}
		model.addAttribute("fqdn", fqdn);
		model.addAttribute("console", console);
		// SSH is bridged directly (xterm.js, see SshWebSocketHandler) rather than
		// through guacd -- RDP/VNC keep the existing Guacamole-canvas page.
		return console.mode() == RemoteConsoleProtocol.SSH ? "connect-terminal" : "connect";
	}
}
