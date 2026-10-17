package io.github.eerlij.heimdall.identity.internal;

import static io.github.eerlij.heimdall.identity.internal.TestLogoutTokens.CLIENT_ID;
import static io.github.eerlij.heimdall.identity.internal.TestLogoutTokens.ISSUER;
import static io.github.eerlij.heimdall.identity.internal.TestLogoutTokens.oidcUser;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eerlij.heimdall.TestcontainersConfiguration;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * Back-channel logout end to end against the embedded server: Keycloak's signed logout token in, Spring's internal
 * re-post to itself with the {@code SESSION} cookie, and the JDBC session row out.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class BackChannelLogoutTests {

    private static final TestLogoutTokens TOKENS = new TestLogoutTokens();

    @LocalServerPort
    int port;

    @Autowired
    FindByIndexNameSessionRepository<?> sessions;

    @Autowired
    JdbcClient jdbc;

    private final String sub = UUID.randomUUID().toString();

    @AfterAll
    static void stopJwks() {
        TOKENS.close();
    }

    @Test
    void signedLogoutTokenDeletesThatSessionOnly() throws Exception {
        String session1 = save("s1");
        String session2 = save("s2");

        HttpResponse<String> response = postLogoutToken(TOKENS.sign(sub, "s1", ISSUER, CLIENT_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(rowExists(session1)).isFalse();
        assertThat(rowExists(session2)).isTrue();
    }

    @Test
    void subjectOnlyTokenDeletesAllUserSessions() throws Exception {
        String session1 = save("s1");
        String session2 = save("s2");
        String otherUser =
                TestSessions.save(sessions, oidcUser(UUID.randomUUID().toString(), "s3", ISSUER, CLIENT_ID));

        HttpResponse<String> response = postLogoutToken(TOKENS.sign(sub, null, ISSUER, CLIENT_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(rowExists(session1)).isFalse();
        assertThat(rowExists(session2)).isFalse();
        assertThat(rowExists(otherUser)).isTrue();
    }

    @Test
    void garbageTokenIs400WithoutSessionOrCsrf() throws Exception {
        HttpResponse<String> response = postLogoutToken("garbage");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"error\":\"invalid_request\"");
    }

    @Test
    void tokenForWrongAudienceDeletesNothing() throws Exception {
        String session1 = save("s1");

        HttpResponse<String> response = postLogoutToken(TOKENS.sign(sub, "s1", ISSUER, "other-client"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(rowExists(session1)).isTrue();
    }

    private String save(String sid) {
        return TestSessions.save(sessions, oidcUser(sub, sid, ISSUER, CLIENT_ID));
    }

    private HttpResponse<String> postLogoutToken(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/logout/connect/back-channel/keycloak"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "logout_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private boolean rowExists(String sessionId) {
        return jdbc.sql("select count(*) from spring_session where session_id = ?")
                        .param(sessionId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** The real registration, but with logout tokens verified against {@link #TOKENS}' key instead of Keycloak's. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwks {

        @Bean
        @Primary
        ClientRegistrationRepository testJwksClientRegistrationRepository(KeycloakProperties keycloak) {
            ClientRegistration registration = new KeycloakClientRegistration()
                    .clientRegistrationRepository(keycloak)
                    .findByRegistrationId(KeycloakClientRegistration.REGISTRATION_ID);
            return new InMemoryClientRegistrationRepository(ClientRegistration.withClientRegistration(registration)
                    .jwkSetUri(TOKENS.jwkSetUri())
                    .build());
        }
    }
}
