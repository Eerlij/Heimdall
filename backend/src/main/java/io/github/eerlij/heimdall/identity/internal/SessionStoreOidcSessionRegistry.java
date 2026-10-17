package io.github.eerlij.heimdall.identity.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Resolves Keycloak logout tokens to application sessions through Spring Session's principal-name index, so
 * back-channel logout keeps working across backend restarts without a table of its own.
 *
 * <p>The principal name is the {@code sub} claim, so a token is looked up by subject and then narrowed with the same
 * issuer, audience and {@code sid} checks as Spring's {@code InMemoryOidcSessionRegistry}.
 */
class SessionStoreOidcSessionRegistry implements OidcSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionStoreOidcSessionRegistry.class);

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    SessionStoreOidcSessionRegistry(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /**
     * Does nothing: the session row <em>is</em> the link. Spring Session creates it, keeps it across session id changes
     * and deletes it on logout or expiry, so nothing here can go stale.
     *
     * @param info ignored
     */
    @Override
    public void saveSessionInformation(OidcSessionInformation info) {}

    /**
     * Returns {@code null}: there is no separate link to remove (see {@link #saveSessionInformation}); upstream
     * callers null-check.
     *
     * @param clientSessionId ignored
     * @return {@code null}
     */
    @Override
    public OidcSessionInformation removeSessionInformation(String clientSessionId) {
        return null;
    }

    /**
     * Finds the sessions a logout token names. They are not deleted here: the back-channel handler's internal request
     * invalidates each one through the normal logout handlers.
     *
     * @param token the validated logout token
     * @return the matching sessions, with no credentials attached
     */
    @Override
    public Iterable<OidcSessionInformation> removeSessionInformation(OidcLogoutToken token) {
        String subject = token.getSubject();
        if (subject == null) {
            // Stricter than upstream (sid alone would do there): the lookup goes through the principal index.
            log.warn("Ignoring back-channel logout token without a subject");
            return List.of();
        }
        String issuer = token.getIssuer().toString();
        List<String> audience = token.getAudience();
        String sid = token.getSessionId();
        List<OidcSessionInformation> matches = new ArrayList<>();
        sessions.findByPrincipalName(subject).forEach((id, session) -> {
            OidcUser user;
            try {
                SecurityContext context =
                        session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                user = (context != null
                                && context.getAuthentication() != null
                                && context.getAuthentication().getPrincipal() instanceof OidcUser oidcUser)
                        ? oidcUser
                        : null;
            } catch (ConversionFailedException | ClassCastException e) {
                // Spring Session JDBC wraps the SerializationFailedException in a ConversionFailedException.
                // Same subject, so fail closed: an unreadable session is useless to its user anyway.
                log.warn("Deleting an unreadable session of a user logged out by Keycloak: {}", e.toString());
                sessions.deleteById(id);
                return;
            }
            if (user != null
                    && user.getIssuer() != null
                    && issuer.equals(user.getIssuer().toString())
                    && user.getAudience() != null
                    && !Collections.disjoint(audience, user.getAudience())
                    && (sid == null || Objects.equals(sid, user.getClaimAsString("sid")))) {
                matches.add(new OidcSessionInformation(id, Map.of(), user));
            }
        });
        return matches;
    }
}
