package com.yourorg.Users;

import java.time.Instant;

/**
 * The publicly safe view of a user.
 *
 * <p>The endpoints previously returned the {@link User} entity directly, which
 * serialises {@code passWordHash} -- so an unauthenticated caller could harvest
 * every BCrypt hash in the database by walking {@code /users/{id}}. Nothing outside
 * the authentication code has any reason to see a password hash, so it is not a
 * field here at all rather than something a future annotation has to remember to
 * hide.
 */
public record UserResponseDTO(
        long id,
        String username,
        String email,
        String status,
        Instant createdAt,
        Instant lastLoginAt
) {
    public static UserResponseDTO from(User user) {
        return new UserResponseDTO(
                user.getId(),
                user.getUserName(),
                user.getEmail(),
                user.getStatus() == null ? null : user.getStatus().name(),
                user.getCreatedAt(),
                user.getLastLoginAt());
    }
}
