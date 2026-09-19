package com.yourorg.Users;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the caller of the current request to a {@link User} row, and enforces
 * that a caller only touches their own data.
 *
 * <p>Why this exists: every endpoint took the user id from the URL and trusted it.
 * Combined with {@code /api/dashboard/**} being {@code permitAll()}, anyone could
 * read anyone's subscriptions by incrementing a number. The id in the path is now
 * checked against the authenticated principal.
 *
 * <p>The principal's username is the user's <b>email</b>, because that is what
 * {@link CustomUserDetailsService} builds the UserDetails with. The JWT subject uses
 * the same value, so the two always agree -- previously the token carried whatever
 * string the user typed at login, which failed validation whenever that was a
 * username rather than an email.
 */
@Component
public class CurrentUserService {

    @Autowired
    private UserRepository userRepository;

    /** @return the authenticated user, or 401 if the request is anonymous. */
    public User require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .or(() -> userRepository.findByUserName(email))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authenticated user no longer exists"));
    }

    public Long requireId() {
        return require().getId();
    }

    /**
     * Confirms the caller owns the account the request names.
     *
     * @param requestedUserId the id from the URL path
     * @return the caller's own id, which is the only id any handler should act on
     */
    public Long requireOwnership(Long requestedUserId) {
        Long ownId = requireId();
        if (requestedUserId != null && !requestedUserId.equals(ownId)) {
            // Deliberately not "no such user": that would confirm which ids exist.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this account");
        }
        return ownId;
    }
}
