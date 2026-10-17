package io.github.eerlij.heimdall.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * The Keycloak OIDC client registration, declared by hand.
 *
 * <p>The issuer is Keycloak's public URL, which containers cannot reach, so OIDC discovery can't be used. Browser-facing
 * endpoints (authorization, end session) and the issuer use the public URL; back-channel calls (token, JWK set) use the
 * internal one. PKCE (S256) is Spring Security's default for this client, and Keycloak enforces it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KeycloakProperties.class)
class KeycloakClientRegistration {

    static final String REGISTRATION_ID = "keycloak";

    /**
     * The single client registration.
     *
     * @param keycloak Keycloak location and client credentials
     * @return a repository holding the {@value #REGISTRATION_ID} registration
     */
    @Bean
    ClientRegistrationRepository clientRegistrationRepository(KeycloakProperties keycloak) {
        // No userInfoUri: the ID token carries the claims, saving one call per login.
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(keycloak.clientId())
                .clientSecret(keycloak.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .userNameAttributeName("sub")
                .issuerUri(keycloak.publicUrl() + "/realms/" + keycloak.realm())
                .authorizationUri(keycloak.publicEndpoint("auth").toString())
                .tokenUri(keycloak.internalEndpoint("token").toString())
                .jwkSetUri(keycloak.internalEndpoint("certs").toString())
                .providerConfigurationMetadata(Map.of(
                        "end_session_endpoint",
                        keycloak.publicEndpoint("logout").toString()))
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    /**
     * An authorized-client store that keeps nothing. Keycloak tokens are used at login only; Boot's default would keep
     * every user's access and refresh token in memory forever.
     *
     * @return a repository that loads nothing and saves nothing
     */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new OAuth2AuthorizedClientRepository() {

            @Override
            public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                    String clientRegistrationId, Authentication principal, HttpServletRequest request) {
                return null;
            }

            @Override
            public void saveAuthorizedClient(
                    OAuth2AuthorizedClient authorizedClient,
                    Authentication principal,
                    HttpServletRequest request,
                    HttpServletResponse response) {}

            @Override
            public void removeAuthorizedClient(
                    String clientRegistrationId,
                    Authentication principal,
                    HttpServletRequest request,
                    HttpServletResponse response) {}
        };
    }
}
