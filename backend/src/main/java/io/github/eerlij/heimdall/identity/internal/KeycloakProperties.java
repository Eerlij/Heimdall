package io.github.eerlij.heimdall.identity.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where Keycloak lives and how the backend authenticates to it.
 *
 * @param publicUrl the URL browsers use (behind nginx); also the token issuer's base
 * @param internalUrl the URL the backend uses for server-to-server calls (token, JWK set)
 * @param realm the realm name
 * @param clientId the confidential BFF client id
 * @param clientSecret the BFF client secret
 */
@ConfigurationProperties("heimdall.keycloak")
@Validated
public record KeycloakProperties(
        @NotNull URI publicUrl,
        @NotNull URI internalUrl,
        @NotBlank String realm,
        @NotBlank String clientId,
        @NotBlank String clientSecret) {

    /**
     * An OpenID Connect endpoint as browsers reach it.
     *
     * @param path the endpoint path below {@code protocol/openid-connect}, e.g. {@code auth}
     * @return the absolute endpoint URI
     */
    public URI publicEndpoint(String path) {
        return endpoint(publicUrl, path);
    }

    /**
     * An OpenID Connect endpoint as the backend reaches it.
     *
     * @param path the endpoint path below {@code protocol/openid-connect}, e.g. {@code token}
     * @return the absolute endpoint URI
     */
    public URI internalEndpoint(String path) {
        return endpoint(internalUrl, path);
    }

    private URI endpoint(URI base, String path) {
        return URI.create(base + "/realms/" + realm + "/protocol/openid-connect/" + path);
    }
}
