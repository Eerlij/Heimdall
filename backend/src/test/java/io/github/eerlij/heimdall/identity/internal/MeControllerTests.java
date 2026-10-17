package io.github.eerlij.heimdall.identity.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.eerlij.heimdall.TestcontainersConfiguration;
import io.github.eerlij.heimdall.identity.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MeControllerTests {

    @Autowired
    MockMvc mvc;

    @Test
    void meReturnsIdNameEmail() throws Exception {
        UUID id = UUID.randomUUID();
        OidcIdToken token = OidcIdToken.withTokenValue("t")
                .subject(UUID.randomUUID().toString())
                .claim("name", "Ada Lovelace")
                .claim("email", "ada@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        mvc.perform(get("/api/me").with(oidcLogin().oidcUser(new AuthenticatedUser(id, List.of(), token, null, "sub"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.email").value("ada@example.com"));
    }
}
