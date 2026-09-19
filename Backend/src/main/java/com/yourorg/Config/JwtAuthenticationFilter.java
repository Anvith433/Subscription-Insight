package com.yourorg.Config;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (isPublic(request.getServletPath())) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String jwtToken = authHeader.substring(7);
        String username;
        try {
            username = jwtService.extractUsername(jwtToken);
        } catch (Exception e) {
            // Malformed, tampered with, or expired. Leave the context unauthenticated
            // and let the authorization layer return 401.
            log.debug("Rejected token: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                if (jwtService.validateToken(jwtToken, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            } catch (Exception e) {
                // A valid signature over a user who has since been deleted. Previously
                // this UsernameNotFoundException escaped the filter and surfaced as a
                // 500; the request should simply be unauthenticated.
                log.debug("Token subject '{}' could not be loaded: {}", username, e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * The previous check was {@code path.contains("/api/signup/**")} -- an Ant pattern
     * compared as a literal substring, so it never matched any real request path and
     * the shortcut was dead code. These are exact paths.
     */
    private boolean isPublic(String path) {
        return "/api/signup".equals(path)
                || "/api/login".equals(path)
                || "/error".equals(path)
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }
}
