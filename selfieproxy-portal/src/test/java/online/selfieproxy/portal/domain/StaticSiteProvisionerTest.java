package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import online.selfieproxy.portal.config.SitesWebserverProperties;

class StaticSiteProvisionerTest {

	@TempDir
	Path tempDir;

	private StaticSiteProvisioner newProvisioner() {
		SitesWebserverProperties properties = new SitesWebserverProperties("127.0.0.1", 8090,
				tempDir.resolve("sites-conf").toString(), tempDir.resolve("sites").toString());
		return new StaticSiteProvisioner(properties);
	}

	@Test
	void contentModeCreatesContentDirectoryAndRootBlock() throws Exception {
		StaticSiteProvisioner provisioner = newProvisioner();

		provisioner.provision("example.com", null);

		assertTrue(Files.isDirectory(tempDir.resolve("sites").resolve("example.com")));
		String conf = Files.readString(tempDir.resolve("sites-conf").resolve("example.com.conf"));
		assertTrue(conf.contains("root /sites/example.com;"), conf);
		assertTrue(conf.contains("index index.html;"), conf);
		assertFalse(conf.contains("return 301"), conf);
	}

	@Test
	void redirectModeWritesReturnBlockAndSkipsContentDirectory() throws Exception {
		StaticSiteProvisioner provisioner = newProvisioner();

		provisioner.provision("example.com", "https://www.example.com");

		assertFalse(Files.exists(tempDir.resolve("sites").resolve("example.com")));
		String conf = Files.readString(tempDir.resolve("sites-conf").resolve("example.com.conf"));
		assertTrue(conf.contains("server_name example.com;"), conf);
		assertTrue(conf.contains("return 301 https://www.example.com$request_uri;"), conf);
		assertFalse(conf.contains("root "), conf);
	}

	@Test
	void renameInRedirectModeSkipsContentDirectoryMove() throws Exception {
		StaticSiteProvisioner provisioner = newProvisioner();
		provisioner.provision("old.example.com", "https://www.example.com");

		provisioner.rename("old.example.com", "new.example.com", "https://www.example.com");

		assertFalse(Files.exists(tempDir.resolve("sites-conf").resolve("old.example.com.conf")));
		String conf = Files.readString(tempDir.resolve("sites-conf").resolve("new.example.com.conf"));
		assertTrue(conf.contains("return 301 https://www.example.com$request_uri;"), conf);
		assertFalse(Files.exists(tempDir.resolve("sites").resolve("new.example.com")));
	}
}
