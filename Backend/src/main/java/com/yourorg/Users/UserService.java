package com.yourorg.Users;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Read/write access to users.
 *
 * <p>The methods were package-private, so nothing outside {@code com.yourorg.Users}
 * could call them -- {@link UserController} only compiled because it happens to live
 * in the same package. They are public now so the service is usable as a service.
 */
@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<User> findByUserName(String userName) {
        return userRepository.findByUserName(userName);
    }

    public Optional<User> findById(long id) {
        return userRepository.findById(id);
    }

    public User save(User user) {
        return userRepository.save(user);
    }
}
