package online.selfieproxy.portal.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.BoringProxyProperties;
import online.selfieproxy.portal.domain.ExposedApp;
import online.selfieproxy.portal.domain.ExposedAppStore;
import online.selfieproxy.portal.domain.TunnelMapper;
import online.selfieproxy.portal.security.NetworkServiceCredentialCipher;

/**
 * Connect action for an SSH/RDP/VNC-mode Network Service, reached from the Applications list
 * (DashboardController/dashboard.html). The live session itself is served by the separate
 * selfieproxy-remote-console service, which only ever reads exposed-apps.json -- so a credential
 * still needs to be entered and encrypted through the portal (the sole writer) whenever none is
 * stored yet, eg. right after adding one with a blank password, or after a configuration import
 * (which never carries a password -- see BackupService). Once a credential is stored, Connect
 * skips this page and goes straight to the console domain.
 */
@Controller
public class ConsoleConnectController {

	private final BoringProxyClient boringProxyClient;
	private final TunnelMapper tunnelMapper;
	private final ExposedAppStore exposedAppStore;
	private final NetworkServiceCredentialCipher cipher;
	private final BoringProxyProperties properties;

	public ConsoleConnectController(BoringProxyClient boringProxyClient, TunnelMapper tunnelMapper,
			ExposedAppStore exposedAppStore, NetworkServiceCredentialCipher cipher, BoringProxyProperties properties) {
		this.boringProxyClient = boringProxyClient;
		this.tunnelMapper = tunnelMapper;
		this.exposedAppStore = exposedAppStore;
		this.cipher = cipher;
		this.properties = properties;
	}

	@GetMapping("/apps/{fqdn}/connect")
	public String connect(@PathVariable String fqdn, Model model) {
		ExposedApp app = reconciledApp(fqdn);
		if (!app.isRemoteAccessMode()) {
			return "redirect:/apps";
		}
		if (app.encryptedSecret() != null) {
			return "redirect:https://" + properties.consoleDomain() + "/connect/" + fqdn;
		}
		model.addAttribute("app", app);
		return "connect-credential";
	}

	@PostMapping("/apps/{fqdn}/connect")
	public String submitCredential(@PathVariable String fqdn, @RequestParam(required = false) String secret) {
		ExposedApp app = reconciledApp(fqdn);
		if (!app.isRemoteAccessMode()) {
			return "redirect:/apps";
		}

		ExposedApp updated = app.withEncryptedSecret(cipher.encrypt(secret));
		exposedAppStore.save(updated);
		return "redirect:https://" + properties.consoleDomain() + "/connect/" + fqdn;
	}

	private ExposedApp reconciledApp(String fqdn) {
		TunnelDto tunnel = boringProxyClient.getTunnel(fqdn);
		return exposedAppStore.reconcile(tunnelMapper.toExposedApp(tunnel));
	}
}
