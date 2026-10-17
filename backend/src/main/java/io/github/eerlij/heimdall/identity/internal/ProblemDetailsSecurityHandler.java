package io.github.eerlij.heimdall.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers security failures with RFC 9457 problems: 401 when authentication is missing, 403 when access is denied
 * (including a missing or invalid CSRF token). The body never carries the exception message.
 */
@Component
class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JacksonJsonHttpMessageConverter converter;

    ProblemDetailsSecurityHandler(JsonMapper jsonMapper) {
        // Rebuilding the application's mapper adds Spring's ProblemDetail mixin, as Spring MVC's converter has it.
        this.converter = new JacksonJsonHttpMessageConverter(jsonMapper.rebuild());
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        write(response, HttpStatus.UNAUTHORIZED);
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN);
    }

    private void write(HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        converter.write(
                ProblemDetail.forStatus(status),
                MediaType.APPLICATION_PROBLEM_JSON,
                new ServletServerHttpResponse(response));
    }
}
