package com.example.myjavaapplication;
public class UserResponse {
    private Long userId;
    private String email;
    private String username;
    public UserResponse(Long userId, String email, String username) {
        this.userId = userId;
        this.email = email;
        this.username = username;
    }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
}
