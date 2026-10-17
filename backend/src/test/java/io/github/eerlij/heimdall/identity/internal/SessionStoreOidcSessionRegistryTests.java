package io.github.eerlij.heimdall.identity.internal;

import static io.github.eerlij.heimdall.identity.internal.TestLogoutTokens.CLIENT_ID;
import static io.github.eerlij.heimdall.identity.internal.TestLogoutTokens.ISSUER;
import static io.github.eerlij.heimdall.identity.internal.TestLogoutTokens.oidcUser;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eerlij.heimdall.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SessionStoreOidcSessionRegistryTests {

    @Autowired
    OidcSessionRegistry registry;

    @Autowired
    FindByIndexNameSessionRepository<?> sessions;

    @Autowired
    JdbcClient jdbc;

    // Fresh per test: sessions from other tests share the database.
    private final String sub = UUID.randomUUID().toString();

    @Test
    void logoutTokenWithSidMatchesOnlyThatSession() {
        String session1 = save(sub, "s1");
        save(sub, "s2");

        assertThat(sessionIds(token(sub, "s1", ISSUER, CLIENT_ID))).containsExactly(session1);
    }

    @Test
    void logoutTokenWithoutSidMatchesAllSessionsOfSubject() {
        String session1 = save(sub, "s1");
        String session2 = save(sub, "s2");
        save(UUID.randomUUID().toString(), "s3");

        assertThat(sessionIds(token(sub, null, ISSUER, CLIENT_ID))).containsExactlyInAnyOrder(session1, session2);
    }

    @Test
    void wrongIssuerOrAudienceMatchesNothing() {
        save(sub, "s1");

        assertThat(sessionIds(token(sub, null, "http://elsewhere/realms/heimdall", CLIENT_ID)))
                .isEmpty();
        assertThat(sessionIds(token(sub, "s1", ISSUER, "other-client"))).isEmpty();
    }

    @Test
    void tokenWithoutSubjectMatchesNothing() {
        save(sub, "s1");

        assertThat(sessionIds(token(null, "s1", ISSUER, CLIENT_ID))).isEmpty();
    }

    @Test
    void unreadableSessionIsDeletedNotFatal() {
        String corrupt = save(sub, "s1");
        String readable = save(sub, "s2");
        jdbc.sql("""
                        update spring_session_attributes set attribute_bytes = :bytes
                        where attribute_name = 'SPRING_SECURITY_CONTEXT'
                          and session_primary_id = (select primary_id from spring_session where session_id = :id)
                        """).param("bytes", new byte[] {1, 2, 3}).param("id", corrupt).update();

        assertThat(sessionIds(token(sub, null, ISSUER, CLIENT_ID))).containsExactly(readable);
        assertThat(sessions.findById(corrupt)).isNull();
    }

    @Test
    void newRegistryInstanceFindsExistingSessions() {
        String session1 = save(sub, "s1");

        var restarted = new SessionStoreOidcSessionRegistry(sessions);

        assertThat(infos(restarted, token(sub, "s1", ISSUER, CLIENT_ID)))
                .extracting(OidcSessionInformation::getSessionId)
                .containsExactly(session1);
    }

    @Test
    void returnedInfoCarriesSessionIdAndNoCredentials() {
        String session1 = save(sub, "s1");

        List<OidcSessionInformation> infos = infos(token(sub, "s1", ISSUER, CLIENT_ID));

        assertThat(infos).singleElement().satisfies(info -> {
            assertThat(info.getSessionId()).isEqualTo(session1);
            assertThat(info.getAuthorities()).isEmpty();
            assertThat(info.getPrincipal().getSubject()).isEqualTo(sub);
        });
    }

    private String save(String subject, String sid) {
        return TestSessions.save(sessions, oidcUser(subject, sid, ISSUER, CLIENT_ID));
    }

    private List<OidcSessionInformation> infos(OidcLogoutToken token) {
        return infos(registry, token);
    }

    private static List<OidcSessionInformation> infos(OidcSessionRegistry registry, OidcLogoutToken token) {
        return StreamSupport.stream(registry.removeSessionInformation(token).spliterator(), false)
                .toList();
    }

    private List<String> sessionIds(OidcLogoutToken token) {
        return infos(token).stream().map(OidcSessionInformation::getSessionId).toList();
    }

    private static OidcLogoutToken token(String sub, String sid, String iss, String aud) {
        OidcLogoutToken.Builder builder = OidcLogoutToken.withTokenValue("logout-token")
                .issuer(iss)
                .audience(List.of(aud))
                .issuedAt(Instant.now())
                .jti(UUID.randomUUID().toString())
                .events(Map.of("http://schemas.openid.net/event/backchannel-logout", Map.of()));
        if (sub != null) {
            builder.subject(sub);
        }
        if (sid != null) {
            builder.sessionId(sid);
        }
        return builder.build();
    }
}
