package io.github.eerlij.heimdall.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.eerlij.heimdall.identity.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class AppUserOidcUserServiceTests {

    private final OidcUserService delegate = mock(OidcUserService.class);
    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final AppUserOidcUserService service = new AppUserOidcUserService(delegate, repository);

    private final ClientRegistration registration = ClientRegistration.withRegistrationId("keycloak")
            .clientId("c")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/cb")
            .authorizationUri("http://localhost/a")
            .tokenUri("http://localhost/t")
            .build();
    private final OidcUserRequest request = new OidcUserRequest(
            registration,
            new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    "x",
                    Instant.now(),
                    Instant.now().plusSeconds(60)),
            idToken("ignored"));

    private static OidcIdToken idToken(String sub) {
        return OidcIdToken.withTokenValue("t")
                .subject(sub)
                .claim("name", "Ada Lovelace")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    @Test
    void returnsAuthenticatedUserWithUpsertedId() {
        UUID sub = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        OidcIdToken token = idToken(sub.toString());
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_X"));
        when(delegate.loadUser(any())).thenReturn(new DefaultOidcUser(authorities, token));
        when(repository.upsert(sub)).thenReturn(id);

        var user = service.loadUser(request);

        assertThat(user)
                .isInstanceOfSatisfying(
                        AuthenticatedUser.class, au -> assertThat(au.userId()).isEqualTo(id));
        assertThat(user.getName()).isEqualTo(sub.toString());
        assertThat(user.getAuthorities().stream().map(Object::toString)).containsExactly("ROLE_X");
        assertThat(user.getIdToken()).isSameAs(token);
    }

    @Test
    void nonUuidSubjectFailsLogin() {
        when(delegate.loadUser(any())).thenReturn(new DefaultOidcUser(List.of(), idToken("not-a-uuid")));

        assertThatThrownBy(() -> service.loadUser(request))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        e -> assertThat(e.getError().getErrorCode()).isEqualTo("invalid_subject"));
        verifyNoInteractions(repository);
    }
}
