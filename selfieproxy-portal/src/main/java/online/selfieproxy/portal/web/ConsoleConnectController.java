package online.selfieproxy.portal.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.domain.Server;
import online.selfieproxy.portal.domain.ServerStore;
import online.selfieproxy.portal.security.NetworkServiceCredentialCipher;

/**
 * Connect action for an Application's Terminal or Remote Desktop protocol, reached from the
 * Applications list (DashboardController/dashboard.html). The live session itself is served by the
 * separate selfieproxy-remote-console service, which only ever reads servers.json -- so a
 * credential still needs to be entered and encrypted through the portal (the sole writer) whenever
 * none is stored yet for that protocol, eg. right after enabling it with a blank password, or after
 * a configuration import (which never carries a password -- see BackupService). Once a credential
 * is stored, Connect skips this page and goes straight to the console domain, dialing that
 * protocol's own hidden tunnel FQDN (never the Application's own public-facing fqdn).
 */
@Controller
public class ConsoleConnectController {

	private final ServerStore serverStore;
	private final NetworkServiceCredentialCipher cipher;
	private final BoringProxyProperties properties;

	public ConsoleConnectController(ServerStore serverStore, NetworkServiceCredentialCipher cipher,
			BoringProxyProperties properties) {
		this.serverStore = serverStore;
		this.cipher = cipher;
		this.properties = properties;
	}

	@GetMapping("/servers/{fqdn}/connect/{protocol}")
	public String connect(@PathVariable String fqdn, @PathVariable String protocol, Model model) {
		Server server = serverStore.find(fqdn);
		if (server == null) {
			return "redirect:/servers";
		}
		return switch (protocol) {
			case "terminal" -> server.hasTerminal()
					? connectOrPrompt(fqdn, protocol, "Terminal", server.terminal().username(),
							server.terminal().encryptedSecret(), server.terminal().fqdn(), model)
					: "redirect:/servers";
			case "remote-desktop" -> server.hasRemoteDesktop()
					? connectOrPrompt(fqdn, protocol, "Remote Desktop", server.remoteDesktop().username(),
							server.remoteDesktop().encryptedSecret(), server.remoteDesktop().fqdn(), model)
					: "redirect:/servers";
			default -> "redirect:/servers";
		};
	}

	private String connectOrPrompt(String fqdn, String protocol, String protocolLabel, String username,
			String encryptedSecret, String hiddenFqdn, Model model) {
		if (encryptedSecret != null) {
			return "redirect:https://" + properties.consoleDomain() + "/connect/" + hiddenFqdn;
		}
		model.addAttribute("fqdn", fqdn);
		model.addAttribute("protocol", protocol);
		model.addAttribute("protocolLabel", protocolLabel);
		model.addAttribute("username", username);
		return "connect-credential";
	}

	@PostMapping("/servers/{fqdn}/connect/{protocol}")
	public String submitCredential(@PathVariable String fqdn, @PathVariable String protocol,
			@RequestParam(required = false) String secret) {
		Server server = serverStore.find(fqdn);
		if (server == null) {
			return "redirect:/servers";
		}

		String hiddenFqdn;
		if ("terminal".equals(protocol) && server.hasTerminal()) {
			hiddenFqdn = server.terminal().fqdn();
			serverStore.save(server.withTerminalCredential(cipher.encrypt(secret)));
		} else if ("remote-desktop".equals(protocol) && server.hasRemoteDesktop()) {
			hiddenFqdn = server.remoteDesktop().fqdn();
			serverStore.save(server.withRemoteDesktopCredential(cipher.encrypt(secret)));
		} else {
			return "redirect:/servers";
		}
		return "redirect:https://" + properties.consoleDomain() + "/connect/" + hiddenFqdn;
	}
}
