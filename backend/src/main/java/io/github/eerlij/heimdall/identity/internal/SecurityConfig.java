package io.github.eerlij.heimdall.identity.internal;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The backend-for-frontend security chain: Keycloak login into a server-side session, RFC 9457 401/403s for the API
 * (never a login redirect), SPA-style CSRF and logout that also ends the Keycloak session.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HeimdallProperties.class)
class SecurityConfig {

    /**
     * The single filter chain; Boot also applies it to the management port.
     *
     * @param http the security builder
     * @param registrations the Keycloak client registration
     * @param authorizedClients the store that keeps no tokens
     * @param problems the 401/403 problem writer
     * @param keycloak Keycloak location and client id
     * @param heimdall the public origin
     * @return the chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            ProblemDetailsSecurityHandler problems,
            KeycloakProperties keycloak,
            HeimdallProperties heimdall)
            throws Exception {
        http.authorizeHttpRequests(requests -> requests.requestMatchers(
                                EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class))
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // Registered before oauth2Login's redirect entry point, so it is also the fallback for everything else.
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(problems, pathPattern("/api/**"))
                        .accessDeniedHandler(problems))
                // The SPA keeps its own return URL; anonymous requests never create a session.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .oauth2Login(login -> login
                        // A custom login page also disables Spring's generated login and logout pages.
                        .loginPage("/oauth2/authorization/" + KeycloakClientRegistration.REGISTRATION_ID)
                        .failureUrl("/?login_error")
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(
                                new UiLocalesAuthorizationRequestResolver(registrations)))
                        .authorizedClientRepository(authorizedClients))
                .csrf(CsrfConfigurer::spa)
                .logout(logout -> logout.logoutSuccessHandler(logoutSuccessHandler(registrations, keycloak, heimdall)));
        return http.build();
    }

    private static OidcClientInitiatedLogoutSuccessHandler logoutSuccessHandler(
            ClientRegistrationRepository registrations, KeycloakProperties keycloak, HeimdallProperties heimdall) {
        OidcClientInitiatedLogoutSuccessHandler handler = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        // Used when the app session already expired, so the SSO session still ends (Keycloak asks to confirm).
        handler.setDefaultTargetUrl(UriComponentsBuilder.fromUri(keycloak.publicEndpoint("logout"))
                .queryParam("client_id", keycloak.clientId())
                .queryParam("post_logout_redirect_uri", heimdall.publicOrigin() + "/")
                .encode()
                .build()
                .toUriString());
        return handler;
    }
}
