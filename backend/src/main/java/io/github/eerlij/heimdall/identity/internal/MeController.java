package io.github.eerlij.heimdall.identity.internal;

import io.github.eerlij.heimdall.identity.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

    record MeResponse(UUID id, String name, String email) {}

    /**
     * The logged-in user's internal id plus the name and email from the ID token (nothing of these is stored).
     *
     * @param user the session principal
     * @return the current user
     */
    @GetMapping("/api/me")
    MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new MeResponse(user.userId(), user.getFullName(), user.getEmail());
    }
}
