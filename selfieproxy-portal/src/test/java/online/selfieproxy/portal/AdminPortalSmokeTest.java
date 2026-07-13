package online.selfieproxy.portal;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.AgentStatusDto;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TokenDataDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;

/**
 * Exercises dashboard -> add -> edit -> delete through real Spring MVC
 * controllers + Thymeleaf templates, with BoringProxyClient mocked so no real
 * BoringProxy server is required. The portal itself no longer checks a
 * password (boringproxy gates the domain via OIDC before a request ever
 * reaches here, see SessionInterceptor) -- each flow starts by simulating
 * that gate with the X-Selfieproxy-Sso-Verified header on a first request,
 * then reuses the resulting HttpSession like a real browser would.
 */
@SpringBootTest(properties = {
		"selfieproxy.exposed-apps-path=${java.io.tmpdir}/selfieproxy-smoke-test-exposed-apps.json"})
@AutoConfigureMockMvc
class AdminPortalSmokeTest {

	private static final String SSO_VERIFIED_HEADER = "X-Selfieproxy-Sso-Verified";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private BoringProxyClient boringProxyClient;

	private MockHttpSession authenticatedSession() throws Exception {
		MvcResult result = mockMvc.perform(get("/apps").header(SSO_VERIFIED_HEADER, "true"))
				.andExpect(status().isOk())
				.andReturn();
		return (MockHttpSession) result.getRequest().getSession();
	}

	@Test
	void loginDashboardAddEditAndDeleteFlow() throws Exception {
		MockHttpSession session = authenticatedSession();

		when(boringProxyClient.listAgents())
				.thenReturn(Map.of("home", new AgentStatusDto(null), "office", new AgentStatusDto(null)));

		TunnelDto webTunnel = new TunnelDto("music.example.com", "admin.example.com", 22, "", "user",
				12345, "", "127.0.0.1", 8096, false, "client", false, "admin", "home", "", "");
		TunnelDto netTunnel = new TunnelDto("ssh.example.com", "admin.example.com", 22, "", "user",
				51234, "", "127.0.0.1", 22, true, "passthrough", false, "admin", "home", "", "");
		when(boringProxyClient.listTunnels())
				.thenReturn(Map.of("music.example.com", webTunnel, "ssh.example.com", netTunnel));

		mockMvc.perform(get("/apps").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("music")))
				.andExpect(content().string(containsString("ssh")))
				.andExpect(content().string(containsString("home")));

		mockMvc.perform(get("/apps/new").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Add application")));

		when(boringProxyClient.getTunnel(eq("music.example.com"))).thenReturn(webTunnel);

		mockMvc.perform(get("/apps/music/edit").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("music")));

		// SSO can only ever gate "Server HTTPS" (Web Application + HTTPS +
		// TlsMode.MANAGED, boringproxy's own "server" TLS termination) --
		// requesting it for a BYO_CERT app must be rejected, never silently
		// dropped, since BYO_CERT/HOP_BY_HOP are HTTPS too and it'd be easy
		// to assume they qualify.
		mockMvc.perform(post("/apps")
						.session(session)
						.param("subdomain", "unprotectable")
						.param("homelabName", "home")
						.param("type", "WEB_APPLICATION")
						.param("protocol", "HTTPS")
						.param("tlsMode", "BYO_CERT")
						.param("ssoProtected", "true")
						.param("host", "127.0.0.1")
						.param("port", "443"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(
						"SSO protection requires Web Application, HTTPS, and the recommended End-to-end encrypted option.")));

		when(boringProxyClient.createTunnel(any(CreateTunnelRequestDto.class))).thenReturn(webTunnel);

		mockMvc.perform(post("/apps")
						.session(session)
						.param("subdomain", "newapp")
						.param("homelabName", "home")
						.param("type", "WEB_APPLICATION")
						.param("protocol", "HTTP")
						.param("host", "127.0.0.1")
						.param("port", "8080"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/apps"));

		mockMvc.perform(post("/apps/ssh/delete").session(session))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/apps"));
	}

	@Test
	void agentsListAddRegenerateAndDeleteFlow() throws Exception {
		MockHttpSession session = authenticatedSession();

		when(boringProxyClient.listAgents()).thenReturn(Map.of("default", new AgentStatusDto(null)));
		when(boringProxyClient.listTokens())
				.thenReturn(Map.of("secret-abc", new TokenDataDto("admin", "default")));

		TunnelDto webTunnel = new TunnelDto("music.example.com", "admin.example.com", 22, "", "user",
				12345, "", "127.0.0.1", 8096, false, "client", false, "admin", "default", "", "");
		when(boringProxyClient.listTunnels()).thenReturn(Map.of("music.example.com", webTunnel));

		mockMvc.perform(get("/").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("default")))
				.andExpect(content().string(containsString("Disconnected")))
				.andExpect(content().string(containsString("<td>1</td>")));

		mockMvc.perform(get("/agents/new").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Add homelab")));

		when(boringProxyClient.createToken(eq("admin"), eq("office"))).thenReturn("secret-xyz");

		mockMvc.perform(post("/agents").session(session).param("name", "office"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/agents/office/edit"));

		mockMvc.perform(post("/agents/default/regenerate-secret").session(session))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/agents/default/edit"));

		mockMvc.perform(post("/agents/default/delete").session(session))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/"));
	}

	@Test
	void renamingAgentRetargetsSecretAndTunnelsInsteadOfMintingANewOneOrOrphaningApps() throws Exception {
		MockHttpSession session = authenticatedSession();

		when(boringProxyClient.listAgents()).thenReturn(Map.of("default", new AgentStatusDto(null)));
		when(boringProxyClient.listTokens())
				.thenReturn(Map.of("secret-abc", new TokenDataDto("admin", "default")));

		TunnelDto webTunnel = new TunnelDto("music.example.com", "admin.example.com", 22, "", "user",
				12345, "", "127.0.0.1", 8096, false, "client", false, "admin", "default", "", "");
		TunnelDto otherHomelabTunnel = new TunnelDto("ssh.example.com", "admin.example.com", 22, "", "user",
				51234, "", "127.0.0.1", 22, true, "passthrough", false, "admin", "office", "", "");
		when(boringProxyClient.listTunnels())
				.thenReturn(Map.of("music.example.com", webTunnel, "ssh.example.com", otherHomelabTunnel));

		mockMvc.perform(post("/agents/default").session(session).param("name", "renamed"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/"));

		verify(boringProxyClient).renameTokenAgent("secret-abc", "renamed");
		verify(boringProxyClient, never()).createToken(anyString(), eq("renamed"));
		verify(boringProxyClient, never()).deleteToken(anyString());

		verify(boringProxyClient).renameTunnelAgent("music.example.com", "renamed");
		verify(boringProxyClient, never()).renameTunnelAgent(eq("ssh.example.com"), anyString());
	}
}
