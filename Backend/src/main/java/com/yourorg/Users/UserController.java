package com.yourorg.Users;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads the caller's own profile.
 *
 * <p>Two things changed. The endpoints returned the {@link User} entity, password
 * hash included; they now return {@link UserResponseDTO}. And they accepted any id,
 * email or username, so one authenticated user could enumerate every other account;
 * lookup by email and by username are gone, and {@code /users/{id}} only answers for
 * the caller's own id.
 */
@RestController
@RequestMapping("/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private CurrentUserService currentUser;

    /** The authenticated user, without needing to know their own id. */
    @GetMapping("/me")
    public UserResponseDTO getCurrentUser() {
        return UserResponseDTO.from(currentUser.require());
    }

    @GetMapping("/{id}")
    public UserResponseDTO getUserById(@PathVariable long id) {
        long ownId = currentUser.requireOwnership(id);
        return userService.findById(ownId)
                .map(UserResponseDTO::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
