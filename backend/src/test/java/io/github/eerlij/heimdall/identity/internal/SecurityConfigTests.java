package io.github.eerlij.heimdall.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.eerlij.heimdall.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SecurityConfigTests.DeniedController.class})
class SecurityConfigTests {

    private static final String KEYCLOAK_OIDC = "http://localhost/auth/realms/heimdall/protocol/openid-connect";

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientRegistrationRepository registrations;

    @Autowired
    FindByIndexNameSessionRepository<?> sessions;

    @Test
    void apiGetWithoutSessionIs401Problem() throws Exception {
        expectProblem(mvc.perform(get("/api/me")), 401);
    }

    @Test
    void apiPostWithoutSessionIs401Problem() throws Exception {
        expectProblem(mvc.perform(post("/api/anything").with(xsrfToken())), 401);
    }

    @Test
    void unknownApiPathWithoutSessionIs401() throws Exception {
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void htmlGetToApiCreatesNoSession() throws Exception {
        mvc.perform(get("/api/me").accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("SESSION"));
    }

    @Test
    void browserNavigationRedirectsToLogin() throws Exception {
        mvc.perform(get("/somewhere").accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/oauth2/authorization/keycloak"));
    }

    @Test
    void nonHtmlNonApiRequestFallsBackTo401() throws Exception {
        expectProblem(mvc.perform(get("/somewhere")), 401);
    }

    @Test
    void loginRedirectsToPublicKeycloakWithPkce() throws Exception {
        UriComponents location = redirectLocation(
                mvc.perform(get("/oauth2/authorization/keycloak")).andExpect(status().isFound()));

        assertThat(location.toUriString()).startsWith(KEYCLOAK_OIDC + "/auth?");
        assertThat(queryParam(location, "client_id")).isEqualTo("heimdall-bff");
        assertThat(queryParam(location, "code_challenge_method")).isEqualTo("S256");
        assertThat(queryParam(location, "code_challenge")).isNotBlank();
        assertThat(queryParam(location, "scope").split(" ")).contains("openid");
        assertThat(queryParam(location, "redirect_uri")).isEqualTo("http://localhost/login/oauth2/code/keycloak");
    }

    @Test
    void redirectUriFollowsForwardedHeaders() throws Exception {
        UriComponents location = redirectLocation(mvc.perform(get("/oauth2/authorization/keycloak")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "example.test"))
                .andExpect(status().isFound()));

        assertThat(queryParam(location, "redirect_uri")).isEqualTo("https://example.test/login/oauth2/code/keycloak");
    }

    @Test
    void responsesCarryXsrfCookie() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    void generatedLoginAndLogoutPagesAreGone() throws Exception {
        String sessionId = TestSessions.save(sessions, user());

        mvc.perform(get("/login").cookie(sessionCookie(sessionId))).andExpect(status().isNotFound());
        mvc.perform(get("/logout").cookie(sessionCookie(sessionId))).andExpect(status().isNotFound());

        assertThat(sessions.findById(sessionId)).isNotNull();
    }

    @Test
    void csrfEndpointReturnsMaskedToken() throws Exception {
        String sessionId = TestSessions.save(sessions, user());

        mvc.perform(get("/api/csrf").cookie(sessionCookie(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parameterName").value("_csrf"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void logoutWithoutCsrfIs403Problem() throws Exception {
        String sessionId = TestSessions.save(sessions, user());

        expectProblem(mvc.perform(post("/logout").cookie(sessionCookie(sessionId))), 403);

        assertThat(sessions.findById(sessionId)).isNotNull();
    }

    @Test
    void formLogoutRedirectsToKeycloakEndSession() throws Exception {
        String sessionId = TestSessions.save(sessions, user());
        MvcResult csrf = mvc.perform(get("/api/csrf").cookie(sessionCookie(sessionId)))
                .andExpect(status().isOk())
                .andReturn();
        String maskedToken = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        Cookie xsrfCookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();
        assertThat(xsrfCookie.getValue()).isNotEqualTo(maskedToken);

        UriComponents location = redirectLocation(mvc.perform(post("/logout")
                        .cookie(sessionCookie(sessionId), xsrfCookie)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("_csrf", maskedToken))
                .andExpect(status().isFound()));

        assertThat(location.toUriString()).startsWith(KEYCLOAK_OIDC + "/logout?");
        assertThat(queryParam(location, "id_token_hint")).isEqualTo("id-token-value");
        assertThat(queryParam(location, "post_logout_redirect_uri")).isEqualTo("http://localhost/");
        assertThat(sessions.findById(sessionId)).isNull();
    }

    @Test
    void logoutWithExpiredSessionStillEndsKeycloakSession() throws Exception {
        UriComponents location =
                redirectLocation(mvc.perform(post("/logout").with(xsrfToken())).andExpect(status().isFound()));

        assertThat(location.toUriString()).startsWith(KEYCLOAK_OIDC + "/logout?");
        assertThat(location.getQueryParams().keySet()).containsExactly("client_id", "post_logout_redirect_uri");
        assertThat(queryParam(location, "client_id")).isEqualTo("heimdall-bff");
        assertThat(queryParam(location, "post_logout_redirect_uri")).isEqualTo("http://localhost/");
    }

    @Test
    void accessDeniedFromControllerIs403Problem() throws Exception {
        expectProblem(
                mvc.perform(get("/api/test/denied")
                        .with(oidcLogin().clientRegistration(registrations.findByRegistrationId("keycloak")))),
                403);
    }

    private static void expectProblem(ResultActions result, int status) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    private static UriComponents redirectLocation(ResultActions result) {
        String location = result.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).isNotNull();
        return UriComponentsBuilder.fromUriString(location).build();
    }

    private static String queryParam(UriComponents uri, String name) {
        List<String> values = uri.getQueryParams().get(name);
        assertThat(values).as(name).hasSize(1);
        return URLDecoder.decode(values.getFirst(), StandardCharsets.UTF_8);
    }

    /**
     * A valid token the way the SPA sends it (cookie echoed in the header). Spring Security's {@code csrf()} post
     * processor is avoided: it swaps the shared filter chain's token repository for a session-backed one, which then
     * leaks into later tests (no {@code XSRF-TOKEN} cookie, sessions for anonymous requests).
     */
    private static RequestPostProcessor xsrfToken() {
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", "test-token"));
            request.addHeader("X-XSRF-TOKEN", "test-token");
            return request;
        };
    }

    private static Cookie sessionCookie(String sessionId) {
        return new Cookie("SESSION", sessionId);
    }

    private static DefaultOidcUser user() {
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token-value")
                .subject("user-1")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
    }

    @RestController
    static class DeniedController {

        @GetMapping("/api/test/denied")
        String denied() {
            throw new AccessDeniedException("test");
        }
    }
}
