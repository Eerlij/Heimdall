package io.github.eerlij.heimdall.identity.internal;

import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.PostgreSqlJdbcIndexedSessionRepositoryCustomizer;

/**
 * Spring Session JDBC tuning on top of Boot's auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
class SessionConfig {

    /**
     * Upserts session attributes. Boot doesn't register it; without it, parallel SPA requests on one session hit
     * duplicate-key errors on attribute writes.
     *
     * @return the PostgreSQL customizer
     */
    @Bean
    PostgreSqlJdbcIndexedSessionRepositoryCustomizer postgreSqlSessionRepositoryCustomizer() {
        return new PostgreSqlJdbcIndexedSessionRepositoryCustomizer();
    }

    /**
     * Keeps the {@code SESSION} cookie value the raw session id, so back-channel logout can replay a stored session id
     * as the cookie.
     *
     * @return the cookie serializer customizer
     */
    @Bean
    DefaultCookieSerializerCustomizer rawSessionIdCookie() {
        return serializer -> serializer.setUseBase64Encoding(false);
    }
}
