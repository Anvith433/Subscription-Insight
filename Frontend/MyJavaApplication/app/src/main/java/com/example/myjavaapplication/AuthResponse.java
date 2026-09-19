package com.example.myjavaapplication;

import com.google.gson.annotations.SerializedName;

/**
 * The body of a successful POST /api/login.
 *
 * <p>{@code userId} and {@code email} are new. Without the id the client had no way to
 * know which account it was logged into, so it fell back to a hardcoded 1L and every
 * install read and wrote user 1's data. {@code email} is what the token's subject
 * contains, which is worth having when diagnosing a 401.
 */
public class AuthResponse {
    @SerializedName("token")
    private String token;

    @SerializedName("username")
    private String username;

    @SerializedName("userId")
    private Long userId;

    @SerializedName("email")
    private String email;

    public AuthResponse(String token, String username) {
        this.token = token;
        this.username = username;
    }

    public AuthResponse(String token, String username, Long userId, String email) {
        this.token = token;
        this.username = username;
        this.userId = userId;
        this.email = email;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    /** Null if the server predates this field; callers must handle that. */
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
