package io.github.eerlij.heimdall.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eerlij.heimdall.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Checks the {@code SESSION} cookie against the embedded server: Boot applies {@code server.servlet.session.cookie.*}
 * to Spring Session's cookie only there (a MockMvc context copies the mock servlet context's cookie config instead).
 */
// Same configuration as ManagementEndpointsTests, so both share one cached context.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class SessionCookieTests {

    @LocalServerPort
    int port;

    @Test
    void sessionCookieFlags() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/oauth2/authorization/keycloak"))
                .build();
        HttpResponse<Void> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(302);
        String sessionCookie = response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("SESSION="))
                .findFirst()
                .orElseThrow();
        List<String> attributes =
                Arrays.stream(sessionCookie.split(";")).map(String::trim).toList();
        assertThat(attributes).contains("HttpOnly", "Secure", "SameSite=Lax");
    }
}
