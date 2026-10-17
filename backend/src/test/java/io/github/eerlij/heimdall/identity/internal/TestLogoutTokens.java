package io.github.eerlij.heimdall.identity.internal;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.eerlij.heimdall.identity.AuthenticatedUser;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Plays Keycloak's side of back-channel logout: an RSA key whose public JWK set is served over HTTP on a random port,
 * and signed logout tokens.
 */
final class TestLogoutTokens implements AutoCloseable {

    static final String ISSUER = "http://localhost/auth/realms/heimdall";
    static final String CLIENT_ID = "heimdall-bff";

    private final RSAKey key;
    private final HttpServer server;

    TestLogoutTokens() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            byte[] jwkSet = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwkSet.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(jwkSet);
                }
            });
            server.start();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the URL of the served JWK set */
    String jwkSetUri() {
        return "http://localhost:" + server.getAddress().getPort() + "/certs";
    }

    /**
     * Signs a logout token the way Keycloak does (RS256, {@code typ: logout+jwt}).
     *
     * @param sub the subject, or {@code null}
     * @param sid the Keycloak session id, or {@code null}
     * @param iss the issuer
     * @param aud the audience
     * @return the compact JWT
     */
    String sign(String sub, String sid, String iss, String aud) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(iss)
                .audience(aud)
                .subject(sub)
                .claim("sid", sid)
                .issueTime(Date.from(Instant.now()))
                .jwtID(UUID.randomUUID().toString())
                .claim("events", Map.of("http://schemas.openid.net/event/backchannel-logout", Map.of()))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(new JOSEObjectType("logout+jwt"))
                        .keyID(key.getKeyID())
                        .build(),
                claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    /**
     * A logged-in user as {@link AppUserOidcUserService} produces it.
     *
     * @param sub the subject
     * @param sid the Keycloak session id
     * @param iss the issuer
     * @param aud the audience
     * @return the user
     */
    static OidcUser oidcUser(String sub, String sid, String iss, String aud) {
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token-value")
                .subject(sub)
                .claim("sid", sid)
                .issuer(iss)
                .audience(List.of(aud))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        return new AuthenticatedUser(
                UUID.randomUUID(), AuthorityUtils.createAuthorityList("OIDC_USER"), idToken, null, "sub");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
