package io.github.eerlij.heimdall.identity.internal;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hands the SPA a masked CSRF token for top-level form posts (logout). XHRs send the {@code XSRF-TOKEN} cookie's value
 * in the {@code X-XSRF-TOKEN} header instead.
 */
@RestController
class CsrfController {

    /**
     * A CSRF token for a hidden form field.
     *
     * @param parameterName the form field name
     * @param token the masked token value
     */
    record CsrfResponse(String parameterName, String token) {}

    /**
     * Returns this request's masked CSRF token.
     *
     * @param token the token Spring Security resolved for this request
     * @return the form field name and masked value
     */
    @GetMapping("/api/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getParameterName(), token.getToken());
    }
}
