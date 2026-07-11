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
 * Exercises login -> dashboard -> add -> edit -> delete through real Spring MVC
 * controllers + Thymeleaf templates, with BoringProxyClient mocked so no real
 * BoringProxy server is required.
 */
@SpringBootTest(properties = {"admin-portal.username=admin", "admin-portal.password=secret",
		"selfieproxy.exposed-apps-path=${java.io.tmpdir}/selfieproxy-smoke-test-exposed-apps.json"})
@AutoConfigureMockMvc
class AdminPortalSmokeTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private BoringProxyClient boringProxyClient;

	@Test
	void loginDashboardAddEditAndDeleteFlow() throws Exception {
		MvcResult loginResult = mockMvc.perform(post("/login").param("username", "admin").param("password", "secret"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/"))
				.andReturn();
		MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

		when(boringProxyClient.listAgents())
				.thenReturn(Map.of("home", new AgentStatusDto(null), "office", new AgentStatusDto(null)));

		TunnelDto webTunnel = new TunnelDto("music.example.com", "admin.example.com", 22, "", "user",
				12345, "", "127.0.0.1", 8096, false, "client", "admin", "home", "", "");
		TunnelDto netTunnel = new TunnelDto("ssh.example.com", "admin.example.com", 22, "", "user",
				51234, "", "127.0.0.1", 22, true, "passthrough", "admin", "home", "", "");
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

		when(boringProxyClient.createTunnel(any(CreateTunnelRequestDto.class))).thenReturn(webTunnel);

		mockMvc.perform(post("/apps")
						.session(session)
						.param("subdomain", "newapp")
						.param("localNetworkName", "home")
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
		MvcResult loginResult = mockMvc.perform(post("/login").param("username", "admin").param("password", "secret"))
				.andExpect(status().is3xxRedirection())
				.andReturn();
		MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

		when(boringProxyClient.listAgents()).thenReturn(Map.of("default", new AgentStatusDto(null)));
		when(boringProxyClient.listTokens())
				.thenReturn(Map.of("secret-abc", new TokenDataDto("admin", "default")));

		TunnelDto webTunnel = new TunnelDto("music.example.com", "admin.example.com", 22, "", "user",
				12345, "", "127.0.0.1", 8096, false, "client", "admin", "default", "", "");
		when(boringProxyClient.listTunnels()).thenReturn(Map.of("music.example.com", webTunnel));

		mockMvc.perform(get("/").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("default")))
				.andExpect(content().string(containsString("Disconnected")))
				.andExpect(content().string(containsString("<td>1</td>")));

		mockMvc.perform(get("/agents/new").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Add local network")));

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
		MvcResult loginResult = mockMvc.perform(post("/login").param("username", "admin").param("password", "secret"))
				.andExpect(status().is3xxRedirection())
				.andReturn();
		MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

		when(boringProxyClient.listAgents()).thenReturn(Map.of("default", new AgentStatusDto(null)));
		when(boringProxyClient.listTokens())
				.thenReturn(Map.of("secret-abc", new TokenDataDto("admin", "default")));

		TunnelDto webTunnel = new TunnelDto("music.example.com", "admin.example.com", 22, "", "user",
				12345, "", "127.0.0.1", 8096, false, "client", "admin", "default", "", "");
		TunnelDto otherNetworkTunnel = new TunnelDto("ssh.example.com", "admin.example.com", 22, "", "user",
				51234, "", "127.0.0.1", 22, true, "passthrough", "admin", "office", "", "");
		when(boringProxyClient.listTunnels())
				.thenReturn(Map.of("music.example.com", webTunnel, "ssh.example.com", otherNetworkTunnel));

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
