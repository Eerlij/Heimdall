package io.github.eerlij.heimdall.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Forwards the SPA's language to Keycloak's login pages: {@code ?ui_locales=<tag>} on the login URL becomes Keycloak's
 * {@code ui_locales} parameter, for allowlisted tags only (anything else is dropped).
 */
class UiLocalesAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String UI_LOCALES = "ui_locales";

    /** SPA tag to Keycloak locale; Keycloak ships {@code messages_pt}, not {@code pt_PT}. */
    private static final Map<String, String> KEYCLOAK_LOCALES = Map.of("en", "en", "pt-PT", "pt");

    private final OAuth2AuthorizationRequestResolver delegate;

    UiLocalesAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withUiLocales(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return withUiLocales(request, delegate.resolve(request, clientRegistrationId));
    }

    private static OAuth2AuthorizationRequest withUiLocales(
            HttpServletRequest request, OAuth2AuthorizationRequest authorizationRequest) {
        String[] values = request.getParameterValues(UI_LOCALES);
        String locale = values != null && values.length == 1 ? KEYCLOAK_LOCALES.get(values[0]) : null;
        if (authorizationRequest == null || locale == null) {
            return authorizationRequest;
        }
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .additionalParameters(params -> params.put(UI_LOCALES, locale))
                .build();
    }
}
