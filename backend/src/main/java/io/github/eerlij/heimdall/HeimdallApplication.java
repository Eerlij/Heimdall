package io.github.eerlij.heimdall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Heimdall backend, a Spring Modulith modular monolith.
 */
@SpringBootApplication
public class HeimdallApplication {

	/**
	 * Secrets without defaults. Spring leaves unresolved placeholders as literal strings in bound properties, so
	 * they are checked up front to fail with a message that names the missing variable.
	 */
	static final String[] REQUIRED_PROPERTIES = {"HEIMDALL_DB_PASSWORD"};

	public static void main(String[] args) {
		create().run(args);
	}

	/**
	 * Builds the application with startup checks for {@link #REQUIRED_PROPERTIES}.
	 *
	 * @return a configured, not yet started application
	 */
	static SpringApplication create() {
		SpringApplication application = new SpringApplication(HeimdallApplication.class);
		application.addInitializers(context -> context.getEnvironment().setRequiredProperties(REQUIRED_PROPERTIES));
		return application;
	}

}
