package io.github.eerlij.heimdall.identity.internal;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-wide settings the identity module needs.
 *
 * @param publicOrigin the browser origin, for URLs built where no request is at hand
 */
@ConfigurationProperties("heimdall")
@Validated
record HeimdallProperties(@NotNull URI publicOrigin) {}
