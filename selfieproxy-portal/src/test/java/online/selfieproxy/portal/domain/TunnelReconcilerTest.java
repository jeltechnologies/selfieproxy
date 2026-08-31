package online.selfieproxy.portal.domain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.CreateTunnelRequestDto;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;
import online.selfieproxy.portal.security.NetworkServiceCredentialCipher;
import online.selfieproxy.portal.config.SitesWebserverProperties;
import online.selfieproxy.portal.config.ThisServerAgentProperties;

@ExtendWith(MockitoExtension.class)
class TunnelReconcilerTest {

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

	private TunnelReconciler newReconciler() {
		TunnelRepairService repairService = new TunnelRepairService(boringProxyClient, tunnelMapper, serverStore,
				localWebsiteStore, thisServerAgentProperties, sitesWebserverProperties);
		return new TunnelReconciler(boringProxyClient, repairService);
	}

	@Test
	void deletesEveryExistingTunnelThenRecreatesEveryDesiredOne() {
		when(boringProxyClient.listTunnels()).thenReturn(Map.of(
				"stale.example.com", new TunnelDto("stale.example.com", null, 0, null, null, 0, null,
						null, 0, false, "server", false, false, "admin", "old-lab", null, null, null, null)));
		Server server = new Server("blog", "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTPS, 443, WebAuthMethod.SSO, null, null, null, null), null, null,
				List.of(new PortForwardingConfig("blog-example-com-1234.example.com", PortForwardingProtocol.TCP,
						1234, 8080, "Minecraft server")));
		when(serverStore.values()).thenReturn(List.of(server));
		when(localWebsiteStore.list()).thenReturn(List.of(new LocalWebsite("www", "example.com", null, false)));

		newReconciler().reconcile();

		verify(boringProxyClient).deleteTunnel("stale.example.com");
		verify(boringProxyClient, times(3)).createTunnel(any());
		verify(boringProxyClient).createTunnel(argThatDomainEquals("blog.example.com"));
		verify(boringProxyClient).createTunnel(argThatDomainEquals("blog-example-com-1234.example.com"));
		verify(boringProxyClient).createTunnel(argThatDomainEquals("www.example.com"));
	}

	@Test
	void aFailedDeleteDoesNotAbortTheRestOfReconciliation() {
		when(boringProxyClient.listTunnels()).thenReturn(Map.of(
				"stale1.example.com", new TunnelDto("stale1.example.com", null, 0, null, null, 0, null,
						null, 0, false, "server", false, false, "admin", "old-lab", null, null, null, null),
				"stale2.example.com", new TunnelDto("stale2.example.com", null, 0, null, null, 0, null,
						null, 0, false, "server", false, false, "admin", "old-lab", null, null, null, null)));
		// A single conditional stub covering every deleteTunnel call -- stubbing "stale1" and
		// leaving "stale2" unstubbed would trip Mockito's strict-stubbing argument-mismatch check
		// (any explicit stub on a method makes every other invocation of it require one too).
		doAnswer(invocation -> {
			if ("stale1.example.com".equals(invocation.getArgument(0))) {
				throw new RuntimeException("boom");
			}
			return null;
		}).when(boringProxyClient).deleteTunnel(any());
		Server server = new Server("blog", "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTPS, 443, WebAuthMethod.SSO, null, null, null, null), null, null, null);
		when(serverStore.values()).thenReturn(List.of(server));
		when(localWebsiteStore.list()).thenReturn(List.of());

		newReconciler().reconcile();

		verify(boringProxyClient).deleteTunnel("stale1.example.com");
		verify(boringProxyClient).deleteTunnel("stale2.example.com");
		verify(boringProxyClient).createTunnel(argThatDomainEquals("blog.example.com"));
	}

	@Test
	void aFailedCreateDoesNotAbortTheRestOfReconciliation() {
		when(boringProxyClient.listTunnels()).thenReturn(Map.of());
		Server blog = new Server("blog", "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTPS, 443, WebAuthMethod.SSO, null, null, null, null), null, null, null);
		Server shop = new Server("shop", "example.com", "lab1", "127.0.0.1",
				new WebConfig(Protocol.HTTPS, 443, WebAuthMethod.SSO, null, null, null, null), null, null, null);
		when(serverStore.values()).thenReturn(List.of(blog, shop));
		when(localWebsiteStore.list()).thenReturn(List.of());
		// Same single-conditional-stub idiom as above, applied to createTunnel instead.
		when(boringProxyClient.createTunnel(any())).thenAnswer(invocation -> {
			CreateTunnelRequestDto request = invocation.getArgument(0);
			if ("shop.example.com".equals(request.domain())) {
				throw new RuntimeException("boom");
			}
			return null;
		});

		newReconciler().reconcile();

		verify(boringProxyClient).createTunnel(argThatDomainEquals("blog.example.com"));
		verify(boringProxyClient).createTunnel(argThatDomainEquals("shop.example.com"));
	}

	@Test
	void noExistingTunnelsAndNoStoredServersOrSitesDoesNothing() {
		when(boringProxyClient.listTunnels()).thenReturn(Map.of());
		when(serverStore.values()).thenReturn(List.of());
		when(localWebsiteStore.list()).thenReturn(List.of());

		newReconciler().reconcile();

		verify(boringProxyClient, never()).deleteTunnel(any());
		verify(boringProxyClient, never()).createTunnel(any());
	}

	private static CreateTunnelRequestDto argThatDomainEquals(String domain) {
		return org.mockito.ArgumentMatchers.argThat(request -> request != null && domain.equals(request.domain()));
	}
}
