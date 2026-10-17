package io.github.eerlij.heimdall.identity.internal;

import io.github.eerlij.heimdall.identity.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Wraps the standard OIDC user loading (claim validation, authority mapping) and replaces the principal with an
 * {@link AuthenticatedUser} carrying the internal user id, creating the app user on first login.
 */
class AppUserOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final AppUserRepository users;

    AppUserOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate, AppUserRepository users) {
        this.delegate = delegate;
        this.users = users;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser oidc = delegate.loadUser(request);
        UUID subject;
        try {
            subject = UUID.fromString(oidc.getSubject());
        } catch (IllegalArgumentException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_subject"), e);
        }
        return new AuthenticatedUser(
                users.upsert(subject), oidc.getAuthorities(), oidc.getIdToken(), oidc.getUserInfo(), "sub");
    }
}
