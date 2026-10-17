package io.github.eerlij.heimdall.identity.internal;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AppUserRepository {

    private final JdbcClient jdbc;

    AppUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Finds or creates the application user for a Keycloak subject. The no-op update makes {@code RETURNING} yield the
     * existing row, so concurrent first logins are safe.
     *
     * @param keycloakSubject the Keycloak {@code sub}
     * @return the internal user id
     */
    UUID upsert(UUID keycloakSubject) {
        return jdbc.sql("""
                        insert into app_user (keycloak_subject) values (:sub)
                        on conflict (keycloak_subject) do update set keycloak_subject = excluded.keycloak_subject
                        returning id""").param("sub", keycloakSubject).query(UUID.class).single();
    }
}
