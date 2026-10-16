package io.github.eerlij.heimdall;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MissingRequiredPropertiesException;

class RequiredPropertiesTests {

    @Test
    void startupFailsNamingTheMissingDatabasePassword() {
        assumeTrue(System.getenv("HEIMDALL_DB_PASSWORD") == null, "HEIMDALL_DB_PASSWORD is set in this shell");

        assertThatThrownBy(() -> HeimdallApplication.create().run("--spring.main.web-application-type=none"))
                .isInstanceOf(MissingRequiredPropertiesException.class)
                .hasMessageContaining("HEIMDALL_DB_PASSWORD");
    }
}
