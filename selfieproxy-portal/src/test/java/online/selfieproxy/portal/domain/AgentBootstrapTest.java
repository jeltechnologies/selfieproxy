package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.AgentStatusDto;
import online.selfieproxy.portal.config.AgentDefaultsProperties;

@ExtendWith(MockitoExtension.class)
class AgentBootstrapTest {

	@Mock
	private BoringProxyClient boringProxyClient;

	@TempDir
	Path tempDir;

	@Test
	void createsDefaultAgentWhenMissing() {
		when(boringProxyClient.listAgents()).thenReturn(Map.of());
		when(boringProxyClient.createToken(eq("admin"), eq("default"))).thenReturn("generated-secret");
		Path markerPath = tempDir.resolve("default-homelab-bootstrapped");

		new AgentBootstrap(boringProxyClient, new AgentDefaultsProperties("default", markerPath.toString()))
				.createDefaultAgentIfMissing();

		verify(boringProxyClient).createAgent("admin", "default");
		verify(boringProxyClient).createToken("admin", "default");
		assertTrue(Files.exists(markerPath), "bootstrap marker should be written after creating the default agent");
	}

	@Test
	void skipsCreationWhenDefaultAgentAlreadyExists() {
		when(boringProxyClient.listAgents()).thenReturn(Map.of("default", new AgentStatusDto(null)));
		Path markerPath = tempDir.resolve("default-homelab-bootstrapped");

		new AgentBootstrap(boringProxyClient, new AgentDefaultsProperties("default", markerPath.toString()))
				.createDefaultAgentIfMissing();

		verify(boringProxyClient, never()).createAgent(eq("admin"), eq("default"));
		verify(boringProxyClient, never()).createToken(eq("admin"), eq("default"));
		assertTrue(Files.exists(markerPath), "bootstrap marker should still be written so bootstrap never re-runs");
	}

	@Test
	void skipsEntirelyWhenAlreadyBootstrapped() throws IOException {
		Path markerPath = tempDir.resolve("default-homelab-bootstrapped");
		Files.writeString(markerPath, "");

		new AgentBootstrap(boringProxyClient, new AgentDefaultsProperties("default", markerPath.toString()))
				.createDefaultAgentIfMissing();

		verifyNoInteractions(boringProxyClient);
	}
}
