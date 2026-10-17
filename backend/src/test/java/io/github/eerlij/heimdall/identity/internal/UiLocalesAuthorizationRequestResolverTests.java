package io.github.eerlij.heimdall.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class UiLocalesAuthorizationRequestResolverTests {

    private final UiLocalesAuthorizationRequestResolver resolver =
            new UiLocalesAuthorizationRequestResolver(new KeycloakClientRegistration()
                    .clientRegistrationRepository(new KeycloakProperties(
                            URI.create("http://localhost/auth"),
                            URI.create("http://keycloak:8080/auth"),
                            "heimdall",
                            "heimdall-bff",
                            "test-only")));

    @Test
    void portugueseIsMappedToKeycloaksPt() {
        OAuth2AuthorizationRequest request = resolve("pt-PT");

        assertThat(request.getAdditionalParameters()).containsEntry("ui_locales", "pt");
        assertThat(request.getAuthorizationRequestUri()).contains("ui_locales=pt");
    }

    @Test
    void englishIsForwarded() {
        assertThat(resolve("en").getAdditionalParameters()).containsEntry("ui_locales", "en");
    }

    @Test
    void unlistedLocaleIsDropped() {
        assertNoUiLocales(resolve("fr"));
    }

    @Test
    void headerInjectionIsDropped() {
        assertNoUiLocales(resolve("pt-PT\r\nX: y"));
    }

    @Test
    void repeatedParameterIsDropped() {
        assertNoUiLocales(resolve("en", "pt-PT"));
    }

    @Test
    void absentParameterAddsNothing() {
        assertNoUiLocales(resolve());
    }

    private OAuth2AuthorizationRequest resolve(String... uiLocales) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/keycloak");
        if (uiLocales.length > 0) {
            request.addParameter("ui_locales", uiLocales);
        }
        OAuth2AuthorizationRequest resolved = resolver.resolve(request);
        assertThat(resolved).isNotNull();
        return resolved;
    }

    private static void assertNoUiLocales(OAuth2AuthorizationRequest request) {
        assertThat(request.getAdditionalParameters()).doesNotContainKey("ui_locales");
        assertThat(request.getAuthorizationRequestUri()).doesNotContain("ui_locales");
    }
}
