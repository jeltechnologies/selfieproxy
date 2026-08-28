package online.selfieproxy.portal;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
import online.selfieproxy.portal.domain.Server;
import online.selfieproxy.portal.domain.ServerStore;
import online.selfieproxy.portal.domain.Protocol;
import online.selfieproxy.portal.domain.TerminalConfig;
import online.selfieproxy.portal.domain.WebAuthMethod;
import online.selfieproxy.portal.domain.WebConfig;

/**
 * Exercises dashboard -> add -> edit -> delete through real Spring MVC
 * controllers + Thymeleaf templates, with BoringProxyClient mocked so no real
 * BoringProxy server is required. The portal itself no longer checks a
 * password (boringproxy gates the domain via OIDC before a request ever
 * reaches here, see SessionInterceptor) -- each flow starts by simulating
 * that gate with the X-Selfieproxy-Sso-Verified header on a first request,
 * then reuses the resulting HttpSession like a real browser would.
 *
 * ServerStore is the sole source of truth for the Applications list now
 * (see its own javadoc) -- fixture Applications are seeded directly into the
 * real, temp-file-backed store rather than assembled from mocked
 * BoringProxyClient tunnels the way the old single-protocol model allowed.
 */
@SpringBootTest(properties = {
		"selfieproxy.servers-path=${java.io.tmpdir}/selfieproxy-smoke-test-servers.json",
		"selfieproxy.domains-path=${java.io.tmpdir}/selfieproxy-smoke-test-domains.json"})
@AutoConfigureMockMvc
class AdminPortalSmokeTest {

	private static final String SSO_VERIFIED_HEADER = "X-Selfieproxy-Sso-Verified";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ServerStore serverStore;

	@MockitoBean
	private BoringProxyClient boringProxyClient;

	private MockHttpSession authenticatedSession() throws Exception {
		MvcResult result = mockMvc.perform(get("/servers").header(SSO_VERIFIED_HEADER, "true"))
				.andExpect(status().isOk())
				.andReturn();
		return (MockHttpSession) result.getRequest().getSession();
	}

	@Test
	void loginDashboardAddEditAndDeleteFlow() throws Exception {
		MockHttpSession session = authenticatedSession();

		when(boringProxyClient.listAgents())
				.thenReturn(Map.of("home", new AgentStatusDto(null), "office", new AgentStatusDto(null)));
		when(boringProxyClient.listTunnels()).thenReturn(Map.of());

		Server musicServer = new Server("music", "example.com", "home", "127.0.0.1",
				new WebConfig(Protocol.HTTPS, 443, WebAuthMethod.NONE, null, null, null, null), null, null, null);
		Server sshServer = new Server("ssh", "example.com", "home", "127.0.0.1", null,
				new TerminalConfig("ssh-example-com-22.example.com", 22, 51234, "user", null), null, null);
		serverStore.save(musicServer);
		serverStore.save(sshServer);

		mockMvc.perform(get("/servers").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("music")))
				.andExpect(content().string(containsString("ssh")))
				.andExpect(content().string(containsString("home")));

		mockMvc.perform(get("/servers/new").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Add server")));

		when(boringProxyClient.getTunnel(eq("music.example.com"))).thenReturn(
				new TunnelDto("music.example.com", "admin.example.com", 22, "", "user",
						12345, "", "127.0.0.1", 443, false, "server", false, false, "admin", "home", "", "", null, null));

		mockMvc.perform(get("/servers/music.example.com/edit").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("music")));

		when(boringProxyClient.createTunnel(any(CreateTunnelRequestDto.class))).thenReturn(
				new TunnelDto("newapp.example.com", "admin.example.com", 22, "", "user",
						12345, "", "127.0.0.1", 8080, false, "server", false, false, "admin", "home", "", "", null, null));

		mockMvc.perform(post("/servers")
						.session(session)
						.param("subdomain", "newapp")
						.param("domain", "example.com")
						.param("homelabName", "home")
						.param("host", "127.0.0.1")
						.param("webEnabled", "true")
						.param("webProtocol", "HTTP")
						.param("webPort", "8080"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/servers"));

		mockMvc.perform(post("/servers/ssh.example.com/delete").session(session))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/servers"));
	}

	@Test
	void agentsListAddRegenerateAndDeleteFlow() throws Exception {
		MockHttpSession session = authenticatedSession();

		when(boringProxyClient.listAgents()).thenReturn(Map.of("default", new AgentStatusDto(null)));
		when(boringProxyClient.listTokens())
				.thenReturn(Map.of("secret-abc", new TokenDataDto("admin", "default")));

		TunnelDto webTunnel = new TunnelDto("music.example.com", "admin.example.com", 22, "", "user",
				12345, "", "127.0.0.1", 8096, false, "client", false, false, "admin", "default", "", "", null, null);
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
	void renamingAgentRetargetsSecretAndTunnelsInsteadOfMintingANewOneOrOrphaningServers() throws Exception {
		MockHttpSession session = authenticatedSession();

		when(boringProxyClient.listAgents()).thenReturn(Map.of("default", new AgentStatusDto(null)));
		when(boringProxyClient.listTokens())
				.thenReturn(Map.of("secret-abc", new TokenDataDto("admin", "default")));

		TunnelDto webTunnel = new TunnelDto("music.example.com", "admin.example.com", 22, "", "user",
				12345, "", "127.0.0.1", 8096, false, "client", false, false, "admin", "default", "", "", null, null);
		TunnelDto otherHomelabTunnel = new TunnelDto("ssh.example.com", "admin.example.com", 22, "", "user",
				51234, "", "127.0.0.1", 22, true, "passthrough", false, false, "admin", "office", "", "", null, null);
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

	/**
	 * A Server can forward several ports at once (up to 8) -- adding 3 must create exactly 3
	 * tunnels, and editing to drop one and change another's homelab-side port must only
	 * delete/recreate the ones that actually changed, leaving the untouched entry's live tunnel
	 * completely alone. boringProxyClient is stubbed with a small in-memory tunnel map so
	 * listTunnels() reflects what createTunnel/deleteTunnel actually did across both requests.
	 */
	@Test
	void multiplePortForwardingEntriesCreateOneTunnelEachAndUnchangedEntriesSurviveAnEdit() throws Exception {
		MockHttpSession session = authenticatedSession();

		when(boringProxyClient.listAgents()).thenReturn(Map.of("home", new AgentStatusDto(null)));

		Map<String, TunnelDto> liveTunnels = new LinkedHashMap<>();
		when(boringProxyClient.listTunnels()).thenAnswer(invocation -> Map.copyOf(liveTunnels));
		when(boringProxyClient.createTunnel(any(CreateTunnelRequestDto.class))).thenAnswer(invocation -> {
			CreateTunnelRequestDto request = invocation.getArgument(0);
			TunnelDto created = new TunnelDto(request.domain(), "admin.example.com", 22, "", "user",
					request.tunnelPort() != null ? request.tunnelPort() : 0, "", "127.0.0.1",
					request.clientPort() != null ? request.clientPort() : 0,
					Boolean.TRUE.equals(request.allowExternalTcp()), request.tlsTermination(), false, false,
					"admin", "home", "", "", null, null);
			liveTunnels.put(request.domain(), created);
			return created;
		});

		mockMvc.perform(post("/servers")
						.session(session)
						.param("subdomain", "raw")
						.param("domain", "example.com")
						.param("homelabName", "home")
						.param("host", "127.0.0.1")
						.param("portForwardingEnabled", "true")
						.param("portForwardingTargetPort", "8001", "8002", "8003")
						.param("portForwardingPublicPort", "20001", "20002", "20003"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/servers"));

		verify(boringProxyClient, times(3)).createTunnel(any(CreateTunnelRequestDto.class));

		// Drop the 20002 entry entirely, keep 20001 unchanged, and change 20003's homelab-side
		// port (8003 -> 9999, same public port so the same fqdn).
		mockMvc.perform(post("/servers/raw.example.com")
						.session(session)
						.param("subdomain", "raw")
						.param("domain", "example.com")
						.param("homelabName", "home")
						.param("host", "127.0.0.1")
						.param("portForwardingEnabled", "true")
						.param("portForwardingTargetPort", "8001", "9999")
						.param("portForwardingPublicPort", "20001", "20003"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/servers"));

		// 20001's tunnel was only ever created once -- the edit left it completely alone.
		verify(boringProxyClient, times(1)).createTunnel(
				argThat(r -> Objects.equals(r.tunnelPort(), 20001)));
		// 20002 was created once during the initial add, then removed on edit -- deleted, never
		// recreated a second time.
		verify(boringProxyClient).deleteTunnel(contains("-20002."));
		verify(boringProxyClient, times(1)).createTunnel(argThat(r -> Objects.equals(r.tunnelPort(), 20002)));
		// 20003 changed its homelab-side port -- same fqdn, so it's deleted once and recreated once
		// (the second createTunnel call for that public port, on top of the initial add).
		verify(boringProxyClient, times(2)).createTunnel(argThat(r -> Objects.equals(r.tunnelPort(), 20003)));
	}
}
