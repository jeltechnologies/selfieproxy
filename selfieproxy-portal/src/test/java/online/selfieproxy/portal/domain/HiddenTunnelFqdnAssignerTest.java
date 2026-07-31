package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import online.selfieproxy.portal.boringproxy.BoringProxyClient;
import online.selfieproxy.portal.boringproxy.dto.TunnelDto;

@ExtendWith(MockitoExtension.class)
class HiddenTunnelFqdnAssignerTest {

	@Mock
	private BoringProxyClient boringProxyClient;

	@Test
	void assignsTheDerivedLabelWhenNothingCollides() {
		when(boringProxyClient.listTunnels()).thenReturn(Map.of());
		HiddenTunnelFqdnAssigner assigner = new HiddenTunnelFqdnAssigner(boringProxyClient);

		String fqdn = assigner.assign("camunda.nordicsnerd.com", "terminal", "nordicsnerd.com", Set.of());

		assertEquals("camunda-nordicsnerd-com-terminal.nordicsnerd.com", fqdn);
	}

	@Test
	void appendsANumericSuffixOnGenuineCollision() {
		TunnelDto existingTunnel = new TunnelDto("camunda-nordicsnerd-com-terminal.nordicsnerd.com", null, 0, null,
				null, 0, null, "127.0.0.1", 22, false, "passthrough", false, false, "admin", "lab1", null, null);
		when(boringProxyClient.listTunnels()).thenReturn(Map.of(existingTunnel.domain(), existingTunnel));
		HiddenTunnelFqdnAssigner assigner = new HiddenTunnelFqdnAssigner(boringProxyClient);

		String fqdn = assigner.assign("camunda.nordicsnerd.com", "terminal", "nordicsnerd.com", Set.of());

		assertEquals("camunda-nordicsnerd-com-terminal-2.nordicsnerd.com", fqdn);
	}

	@Test
	void excludedFqdnsNeverCountAsACollisionAgainstThemselves() {
		String ownFqdn = "camunda-nordicsnerd-com-terminal.nordicsnerd.com";
		TunnelDto ownTunnel = new TunnelDto(ownFqdn, null, 0, null, null, 0, null, "127.0.0.1", 22, false,
				"passthrough", false, false, "admin", "lab1", null, null);
		when(boringProxyClient.listTunnels()).thenReturn(Map.of(ownFqdn, ownTunnel));
		HiddenTunnelFqdnAssigner assigner = new HiddenTunnelFqdnAssigner(boringProxyClient);

		String fqdn = assigner.assign("camunda.nordicsnerd.com", "terminal", "nordicsnerd.com", Set.of(ownFqdn));

		assertEquals(ownFqdn, fqdn, "re-saving unchanged must not self-collide");
	}

	@Test
	void differentPortForwardingEntriesUnderTheSameAppGetDistinctFqdnsFromTheirOwnPortNumber() {
		when(boringProxyClient.listTunnels()).thenReturn(Map.of());
		HiddenTunnelFqdnAssigner assigner = new HiddenTunnelFqdnAssigner(boringProxyClient);

		String first = assigner.assign("camunda.nordicsnerd.com", "8080", "nordicsnerd.com", Set.of());
		String second = assigner.assign("camunda.nordicsnerd.com", "8081", "nordicsnerd.com", Set.of(first));

		assertEquals("camunda-nordicsnerd-com-8080.nordicsnerd.com", first);
		assertEquals("camunda-nordicsnerd-com-8081.nordicsnerd.com", second);
	}
}
