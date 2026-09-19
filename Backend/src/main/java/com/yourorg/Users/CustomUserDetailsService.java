package com.yourorg.Users;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String loginInput) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(loginInput)
                .orElseGet(() -> userRepository.findByUserName(loginInput)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with: " + loginInput)));

      
        return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                .password(user.getPassWordHash())
                .authorities("USER") 
                .build();
    }
}