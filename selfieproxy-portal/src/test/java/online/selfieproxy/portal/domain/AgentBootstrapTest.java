package online.selfieproxy.portal.domain;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.AgentStatusDto;
import online.selfieproxy.portal.config.AgentDefaultsProperties;

@ExtendWith(MockitoExtension.class)
class AgentBootstrapTest {

	@Mock
	private BoringProxyClient boringProxyClient;

	@Test
	void createsDefaultAgentWhenMissing() {
		when(boringProxyClient.listAgents()).thenReturn(Map.of());
		when(boringProxyClient.createToken(eq("admin"), eq("default"))).thenReturn("generated-secret");

		new AgentBootstrap(boringProxyClient, new AgentDefaultsProperties("default"))
				.createDefaultAgentIfMissing();

		verify(boringProxyClient).createAgent("admin", "default");
		verify(boringProxyClient).createToken("admin", "default");
	}

	@Test
	void skipsCreationWhenDefaultAgentAlreadyExists() {
		when(boringProxyClient.listAgents()).thenReturn(Map.of("default", new AgentStatusDto(null)));

		new AgentBootstrap(boringProxyClient, new AgentDefaultsProperties("default"))
				.createDefaultAgentIfMissing();

		verify(boringProxyClient, never()).createAgent(eq("admin"), eq("default"));
		verify(boringProxyClient, never()).createToken(eq("admin"), eq("default"));
	}
}
