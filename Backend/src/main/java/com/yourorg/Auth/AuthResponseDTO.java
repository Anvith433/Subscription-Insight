package com.yourorg.Auth;

public class AuthResponseDTO {
    private String token;
    private String username;

    /**
     * The account's id. Added because the Android client had no way to learn it and
     * consequently shipped a hardcoded {@code loggedInUserId = 1L}, so every install
     * read and wrote user 1's data.
     */
    private Long userId;

    /** The value carried in the token's subject; useful when debugging a 401. */
    private String email;

    public AuthResponseDTO() {
    }

    public AuthResponseDTO(String token, String username) {
        this.token = token;
        this.username = username;
    }

    public AuthResponseDTO(String token, String username, Long userId, String email) {
        this.token = token;
        this.username = username;
        this.userId = userId;
        this.email = email;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
