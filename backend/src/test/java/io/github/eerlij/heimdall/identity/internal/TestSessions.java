package io.github.eerlij.heimdall.identity.internal;

import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Creates persisted, logged-in sessions as if the user had completed the Keycloak login, for tests that need a real
 * {@code SESSION} cookie (logout, back-channel logout) rather than a mocked principal.
 */
public final class TestSessions {

    private TestSessions() {}

    /**
     * Saves a session holding a security context for {@code user}, authenticated through the {@code keycloak}
     * registration.
     *
     * @param sessions the application's session repository
     * @param user the logged-in user
     * @return the session id, usable as the raw {@code SESSION} cookie value
     */
    public static String save(FindByIndexNameSessionRepository<?> sessions, OidcUser user) {
        return saveTyped(sessions, user);
    }

    private static <S extends Session> String saveTyped(FindByIndexNameSessionRepository<S> sessions, OidcUser user) {
        S session = sessions.createSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new OAuth2AuthenticationToken(
                        user, user.getAuthorities(), KeycloakClientRegistration.REGISTRATION_ID)));
        sessions.save(session);
        return session.getId();
    }
}
