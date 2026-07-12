package online.selfieproxy.ssoserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SsoServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(SsoServerApplication.class, args);
	}
}
