package io.github.eerlij.heimdall.identity.internal;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OidcBackChannelLogoutHandler;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The backend-for-frontend security chain: Keycloak login into a server-side session, RFC 9457 401/403s for the API
 * (never a login redirect), SPA-style CSRF, logout that also ends the Keycloak session, and Keycloak's back-channel
 * logout ({@code POST /logout/connect/back-channel/keycloak}) ending app sessions.
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
     * @param appUsers the application user store
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
            HeimdallProperties heimdall,
            AppUserRepository appUsers)
            throws Exception {
        var oidcUserService = new AppUserOidcUserService(new OidcUserService(), appUsers);
        http.authorizeHttpRequests(requests -> requests.requestMatchers(
                                EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class))
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        // Expired-session logout still needs a masked token. The cookie token store
                        // and NullRequestCache create no session row, and the
                        // response is only readable same-origin.
                        .requestMatchers(HttpMethod.GET, "/api/csrf")
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
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                        .authorizedClientRepository(authorizedClients))
                .csrf(CsrfConfigurer::spa)
                .logout(logout -> logout.logoutSuccessHandler(logoutSuccessHandler(registrations, keycloak, heimdall)))
                // Picks up the OidcBackChannelLogoutHandler bean; calling logoutUri(..) here would replace it.
                .oidcLogout(oidc -> oidc.backChannel(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Back-channel logout's session lookup, backed by the session store so it survives restarts.
     *
     * @param sessions the Spring Session repository
     * @return the registry
     */
    @Bean
    OidcSessionRegistry oidcSessionRegistry(FindByIndexNameSessionRepository<? extends Session> sessions) {
        return new SessionStoreOidcSessionRegistry(sessions);
    }

    /**
     * Ends each matched session by re-posting the logout token to this app with the session's {@code SESSION} cookie,
     * which runs the normal logout handlers.
     *
     * <p>Upstream limits: its {@code RestTemplate} has no timeouts, and each logout re-fetches the JWK set once per
     * session (the internal request validates the token again).
     *
     * @param registry the session lookup
     * @return the handler
     */
    @Bean
    OidcBackChannelLogoutHandler oidcBackChannelLogoutHandler(OidcSessionRegistry registry) {
        OidcBackChannelLogoutHandler handler = new OidcBackChannelLogoutHandler(registry);
        handler.setSessionCookieName("SESSION");
        // Spring re-posts to itself; the host Keycloak used (e.g. host.docker.internal) may not resolve from inside.
        handler.setLogoutUri("{baseScheme}://localhost{basePort}/logout/connect/back-channel/{registrationId}");
        return handler;
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
