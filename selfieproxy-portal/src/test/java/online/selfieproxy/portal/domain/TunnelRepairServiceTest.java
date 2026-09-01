package online.selfieproxy.portal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.config.SitesWebserverProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;
import online.selfieproxy.portal.security.NetworkServiceCredentialCipher;

@ExtendWith(MockitoExtension.class)
class TunnelRepairServiceTest {

	@Mock
	private BoringProxyClient boringProxyClient;
	@Mock
	private ServerStore serverStore;
	@Mock
	private LocalWebsiteStore localWebsiteStore;

	private final TunnelMapper tunnelMapper = new TunnelMapper(new NetworkServiceCredentialCipher("target/test-cipher-key"));
	private final ThisServerAgentProperties thisServerAgentProperties =
			new ThisServerAgentProperties("selfieproxy-internal-agent", "/dev/null");
	private final SitesWebserverProperties sitesWebserverProperties =
			new SitesWebserverProperties("127.0.0.1", 8090, "/sites-conf", "/sites");

	private TunnelRepairService newService() {
		return new TunnelRepairService(boringProxyClient, tunnelMapper, serverStore, localWebsiteStore,
				thisServerAgentProperties, sitesWebserverProperties);
	}

	private static Server webServer(String subdomain) {
		return new Server(subdomain, "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTPS, 443, WebAuthMethod.SSO, null, null, null, null, null), null, null, null);
	}

	private static TunnelDto tunnel(String domain, int tunnelPort) {
		return new TunnelDto(domain, null, 0, null, null, tunnelPort, null, null, 0, false, "server", false, false,
				"admin", "lab1", null, null, null, null);
	}

	@Test
	void createsOnlyTheTunnelsBoringProxyIsMissing() {
		when(boringProxyClient.listTunnels()).thenReturn(Map.of("blog.example.com", tunnel("blog.example.com", 0)));
		when(serverStore.values()).thenReturn(List.of(webServer("blog"), webServer("shop")));
		when(localWebsiteStore.list()).thenReturn(List.of());
		when(boringProxyClient.createTunnel(any())).thenReturn(tunnel("shop.example.com", 0));

		assertThat(newService().createMissing()).isEqualTo(1);

		verify(boringProxyClient).createTunnel(argThatDomainEquals("shop.example.com"));
		verify(boringProxyClient, never()).createTunnel(argThatDomainEquals("blog.example.com"));
	}

	@Test
	void sweepWaitsForASecondSightingBeforeCreatingAnything() {
		when(boringProxyClient.listTunnels()).thenReturn(Map.of());
		when(serverStore.values()).thenReturn(List.of(webServer("blog")));
		when(localWebsiteStore.list()).thenReturn(List.of());
		TunnelRepairService service = newService();

		service.sweep();

		verify(boringProxyClient, never()).createTunnel(any());

		service.sweep();

		verify(boringProxyClient).createTunnel(argThatDomainEquals("blog.example.com"));
	}

	@Test
	void aTunnelThatCameBackOnItsOwnIsNeverRecreated() {
		when(boringProxyClient.listTunnels())
				.thenReturn(Map.of())
				.thenReturn(Map.of("blog.example.com", tunnel("blog.example.com", 0)));
		when(serverStore.values()).thenReturn(List.of(webServer("blog")));
		when(localWebsiteStore.list()).thenReturn(List.of());
		TunnelRepairService service = newService();

		service.sweep();
		service.sweep();

		verify(boringProxyClient, never()).createTunnel(any());
	}

	@Test
	void recreatingATerminalTunnelPersistsItsNewExposedPort() {
		Server server = new Server("blog", "example.com", "lab1", "127.0.0.1", null,
				new TerminalConfig("blog-example-com-ssh.example.com", 22, 40000, "root", null), null, null);
		when(boringProxyClient.listTunnels()).thenReturn(Map.of());
		when(serverStore.values()).thenReturn(List.of(server));
		when(localWebsiteStore.list()).thenReturn(List.of());
		when(boringProxyClient.createTunnel(any())).thenReturn(tunnel("blog-example-com-ssh.example.com", 41234));

		newService().createMissing();

		ArgumentCaptor<Server> saved = ArgumentCaptor.forClass(Server.class);
		verify(serverStore).save(saved.capture());
		assertThat(saved.getValue().terminal().exposedPort()).isEqualTo(41234);
	}

	@Test
	void aFailedCreateLeavesTheRestOfTheRepairAlone() {
		when(boringProxyClient.listTunnels()).thenReturn(Map.of());
		when(serverStore.values()).thenReturn(List.of(webServer("blog"), webServer("shop")));
		when(localWebsiteStore.list()).thenReturn(List.of());
		when(boringProxyClient.createTunnel(any())).thenAnswer(invocation -> {
			CreateTunnelRequestDto request = invocation.getArgument(0);
			if ("blog.example.com".equals(request.domain())) {
				throw new RuntimeException("boom");
			}
			return tunnel(request.domain(), 0);
		});

		assertThat(newService().createMissing()).isEqualTo(1);

		verify(boringProxyClient).createTunnel(argThatDomainEquals("shop.example.com"));
	}

	private static CreateTunnelRequestDto argThatDomainEquals(String domain) {
		return org.mockito.ArgumentMatchers.argThat(request -> request != null && domain.equals(request.domain()));
	}
}
