package online.selfieproxy.portal.web;

import java.net.URI;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import online.selfieproxy.portal.domain.Theme;
import online.selfieproxy.portal.domain.ThemeStore;

/**
 * The Settings menu's theme toggle (fragments/layout.html): flips the shared Light/Dark UI theme
 * (ThemeStore) -- also applied to selfieproxy-identity-provider's login/change-password/logged-out
 * pages, since both read the same persisted setting. A one-click toggle rather than a picker page,
 * since there are only two modes.
 */
@Controller
public class AppearanceController {

	private final ThemeStore themeStore;

	public AppearanceController(ThemeStore themeStore) {
		this.themeStore = themeStore;
	}

	@PostMapping("/appearance/toggle")
	public String toggle(@RequestHeader(value = "Referer", required = false) String referer) {
		Theme current = themeStore.load();
		themeStore.save(current == Theme.LIGHT ? Theme.DARK : Theme.LIGHT);
		return "redirect:" + refererPath(referer);
	}

	/** Only the path+query of Referer is ever reused -- never the host, so this can't be turned into an open redirect. */
	private String refererPath(String referer) {
		if (referer == null || referer.isBlank()) {
			return "/";
		}
		try {
			URI uri = URI.create(referer);
			String path = uri.getRawPath();
			if (path == null || path.isBlank()) {
				return "/";
			}
			String query = uri.getRawQuery();
			return query != null ? path + "?" + query : path;
		} catch (IllegalArgumentException e) {
			return "/";
		}
	}
}
