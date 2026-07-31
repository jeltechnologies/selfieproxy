package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HiddenTunnelLabelTest {

	@Test
	void terminalUsesFixedSuffixRegardlessOfPort() {
		assertEquals("camunda-nordicsnerd-com-terminal", HiddenTunnelLabel.derive("camunda.nordicsnerd.com", "terminal"));
	}

	@Test
	void remoteDesktopUsesFixedSuffixRegardlessOfPort() {
		assertEquals("camunda-nordicsnerd-com-remotedesktop", HiddenTunnelLabel.derive("camunda.nordicsnerd.com", "remotedesktop"));
	}

	@Test
	void portForwardingStillUsesTheNumericPortAsSuffix() {
		assertEquals("camunda-nordicsnerd-com-34891", HiddenTunnelLabel.derive("camunda.nordicsnerd.com", "34891"));
	}

	@Test
	void neverLeaksAPortNumberInATerminalOrRemoteDesktopLabel() {
		String terminalLabel = HiddenTunnelLabel.derive("camunda.nordicsnerd.com", "terminal");
		String remoteDesktopLabel = HiddenTunnelLabel.derive("camunda.nordicsnerd.com", "remotedesktop");
		assertFalse(terminalLabel.matches(".*-\\d+$"), "terminal label must not end in a numeric port: " + terminalLabel);
		assertFalse(remoteDesktopLabel.matches(".*-\\d+$"), "remote desktop label must not end in a numeric port: " + remoteDesktopLabel);
	}

	@Test
	void truncatesAnOverlongBaseRatherThanExceedingTheDnsLabelLimit() {
		String longFqdn = "a-very-long-subdomain-that-goes-on-and-on-and-on.example.com";
		String label = HiddenTunnelLabel.derive(longFqdn, "remotedesktop");
		assertTrue(label.length() <= 59, "label must stay within the 63-char DNS limit minus room for a collision suffix: " + label);
		assertTrue(label.endsWith("-remotedesktop"), "truncation must not eat into the suffix itself: " + label);
	}
}
