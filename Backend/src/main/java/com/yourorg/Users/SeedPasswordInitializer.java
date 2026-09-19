package com.yourorg.Users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Makes the seeded demo accounts usable.
 *
 * <p>{@code V2__seed_data.sql} inserts {@code passwordhash = 'hashed_password'} -- the
 * literal words, not a hash. BCrypt cannot verify that string against anything, so
 * every seeded user was permanently unable to log in, and the sample subscriptions
 * attached to them were unreachable through the API.
 *
 * <p>Fixing it in SQL would mean committing a BCrypt digest to a migration file.
 * Hashing here instead uses the application's own {@link PasswordEncoder}, so the
 * value is guaranteed to verify rather than merely look plausible.
 *
 * <p>Off by default and gated on {@code app.seed.fix-passwords}: it must not run
 * where real accounts exist. It only ever touches rows whose stored hash is exactly
 * the placeholder, so a real password can never be overwritten.
 */
@Configuration
@ConditionalOnProperty(name = "app.seed.fix-passwords", havingValue = "true")
public class SeedPasswordInitializer {

    private static final Logger log = LoggerFactory.getLogger(SeedPasswordInitializer.class);

    /** The exact placeholder written by V2__seed_data.sql. */
    private static final String PLACEHOLDER = "hashed_password";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${app.seed.password:password123}")
    private String seedPassword;

    @Bean
    public ApplicationRunner fixSeedPasswords() {
        return args -> {
            int fixed = 0;
            for (User user : userRepository.findAll()) {
                if (PLACEHOLDER.equals(user.getPassWordHash())) {
                    user.setPassWordHash(passwordEncoder.encode(seedPassword));
                    userRepository.save(user);
                    fixed++;
                    log.warn("Seed account '{}' can now log in with the configured development password.",
                            user.getEmail());
                }
            }
            if (fixed == 0) {
                log.info("No seed placeholder passwords found; nothing to repair.");
            } else {
                log.warn("Repaired {} seed password(s). Disable app.seed.fix-passwords outside development.", fixed);
            }
        };
    }
}
