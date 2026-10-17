package io.github.eerlij.heimdall.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eerlij.heimdall.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@JdbcTest
@Import({TestcontainersConfiguration.class, AppUserRepository.class})
class AppUserRepositoryTests {

    @Autowired
    AppUserRepository repository;

    @Autowired
    JdbcClient jdbc;

    @Test
    void upsertCreatesThenReturnsSameId() {
        UUID subject = UUID.randomUUID();

        UUID first = repository.upsert(subject);
        UUID second = repository.upsert(subject);

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("select count(*) from app_user where keycloak_subject = :s")
                        .param("s", subject)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void differentSubjectsGetDifferentIds() {
        assertThat(repository.upsert(UUID.randomUUID())).isNotEqualTo(repository.upsert(UUID.randomUUID()));
    }
}
