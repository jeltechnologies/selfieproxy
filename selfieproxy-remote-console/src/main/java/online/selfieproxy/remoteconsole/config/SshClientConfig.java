package online.selfieproxy.remoteconsole.config;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single, shared SshClient for the direct SSH terminal path (see
 * SshWebSocketHandler) -- one client opens a new ClientSession per browser
 * connection, same as one guacd daemon serves every RDP/VNC session. Host key
 * verification is deliberately accept-any: this system already trusts the
 * tunnel/agent implicitly (the RDP path already sets ignore-cert/security=any
 * for the same reason), so there is no new host-key-pinning UX to add here
 * that doesn't already exist for the other protocols.
 */
@Configuration
public class SshClientConfig {

	@Bean(destroyMethod = "stop")
	public SshClient sshClient() {
		SshClient client = SshClient.setUpDefaultClient();
		client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
		client.start();
		return client;
	}
}
