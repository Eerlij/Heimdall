package io.github.eerlij.heimdall.identity;

import java.io.Serial;
import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/** The logged-in user as held in the session; {@code getName()} stays the Keycloak {@code sub}. */
public final class AuthenticatedUser extends DefaultOidcUser {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;

    /**
     * Creates the principal.
     *
     * @param userId the internal user id
     * @param authorities granted authorities
     * @param idToken the ID token
     * @param userInfo the user info, may be null
     * @param nameAttributeKey the claim used as the name
     */
    public AuthenticatedUser(
            UUID userId,
            Collection<? extends GrantedAuthority> authorities,
            OidcIdToken idToken,
            OidcUserInfo userInfo,
            String nameAttributeKey) {
        super(authorities, idToken, userInfo, nameAttributeKey);
        this.userId = userId;
    }

    /**
     * The internal id every owned row references; other modules use this, never {@code sub}.
     *
     * @return the internal user id
     */
    public UUID userId() {
        return userId;
    }
}
