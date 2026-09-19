package com.yourorg.Auth;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yourorg.Config.JwtService;
import com.yourorg.Users.User;
import com.yourorg.Users.UserRepository;

@RestController
@RequestMapping("/api")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequestDTO signupRequest) {
        if (isBlank(signupRequest.getUsername())
                || isBlank(signupRequest.getEmail())
                || isBlank(signupRequest.getPassword())) {
            return ResponseEntity.badRequest().body("Username, email and password are all required.");
        }

        if (userRepository.findByUserName(signupRequest.getUsername()).isPresent()) {
            return ResponseEntity.badRequest().body("Error: Username is already taken!");
        }
        // The email column is UNIQUE and is the identity the whole auth path keys on,
        // but only the username was being checked -- a duplicate email reached the
        // database and came back as a 500 constraint violation.
        if (userRepository.findByEmail(signupRequest.getEmail()).isPresent()) {
            return ResponseEntity.badRequest().body("Error: Email is already registered!");
        }

        User user = new User();
        user.setUserName(signupRequest.getUsername());
        user.setEmail(signupRequest.getEmail());
        user.setPassWordHash(passwordEncoder.encode(signupRequest.getPassword()));
        userRepository.save(user);

        return ResponseEntity.ok("User registered successfully!");
    }

    /**
     * Accepts either a username or an email in the {@code username} field, because
     * {@code CustomUserDetailsService} resolves both.
     *
     * <p>Three fixes here. The token's subject was {@code authRequest.getUsername()} --
     * whatever the user typed. But the UserDetails username is always the account's
     * email, so {@code validateToken} compared "anvith" against
     * "anvith@example.com" and failed: a user who logged in with their username got a
     * token that 401'd on every subsequent request. The subject is now the canonical
     * email, looked up after authentication succeeds.
     *
     * <p>And an authentication failure threw out of the handler as a 500. Wrong
     * credentials are an expected outcome, not a server fault; it returns 401.
     *
     * <p>Third, a database outage is no longer reported as a rejected password. See
     * the {@code InternalAuthenticationServiceException} branch below.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequestDTO authRequest) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(authRequest.getUsername(), authRequest.getPassword()));
        } catch (InternalAuthenticationServiceException e) {
            // ProviderManager wraps anything the UserDetailsService throws that is not
            // itself an AuthenticationException -- a JDBC CommunicationsException, for
            // instance -- in this type, which extends AuthenticationException. Catching
            // only the broad type therefore reported "MySQL is unreachable" to the
            // client as "Invalid username or password", sending whoever is debugging to
            // reset passwords that were never the problem. Observed exactly that during
            // verification when the database container stopped responding.
            log.error("Login could not be processed: the authentication backend failed", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Sign-in is temporarily unavailable. Please try again shortly.");
        } catch (AuthenticationException e) {
            // One message for both "no such user" and "wrong password", so the
            // response cannot be used to discover which accounts exist.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid username or password.");
        }

        User user = userRepository.findByEmail(authRequest.getUsername())
                .or(() -> userRepository.findByUserName(authRequest.getUsername()))
                .orElse(null);
        if (user == null) {
            // Authentication passed, so the row existed a moment ago. Treat the race
            // as a failed login rather than a crash.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid username or password.");
        }

        // The column existed and was never written to, so "last seen" was always null.
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        String token = jwtService.generateToken(user.getEmail());
        return ResponseEntity.ok(new AuthResponseDTO(
                token, user.getUserName(), user.getId(), user.getEmail()));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
