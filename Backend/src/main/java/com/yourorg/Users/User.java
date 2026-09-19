package com.yourorg.Users;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.*;


@Entity
@Table(name="users",
      indexes = {
        @Index(name = "idx_users_email", columnList = "email")
    }
)
public class User {

    @Id
    @GeneratedValue(strategy =  GenerationType.IDENTITY)
    @Column(name="id")
    private long id;

    @Column(name="username", nullable= false, unique=true,length=255)
    private String userName;

    @Column(name="email", nullable=false, unique=true, length=255)
    private String email;

    // No unique constraint: a password hash is not an identity. BCrypt salts every
    // hash so collisions are already implausible, and the users table has no such
    // constraint -- declaring one here only misleads readers.
    @Column(name="passwordhash", nullable=false, length=255)
    private String passWordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=50)
    private UserStatus status = UserStatus.ACTIVE;

      @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

      @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public User(){}

    public User(String userName, String email, String passWordHash)
    {
             this.status = UserStatus.ACTIVE;
             this.createdAt = Instant.now();
             this.userName=userName;
             this.email=email;
             this.passWordHash=passWordHash;
             this.lastLoginAt=null;

    }
    public long getId()
    {
        return id;
    }
    public void setId(long id)
    {
        this.id=id;
    }
    public String getUserName()
    {
        return userName;
    }
    public void setUserName(String userName)
    {
        this.userName=userName;
    }
    public String getEmail()
    {
        return email;
    }
    public void setEmail(String email)
    {
        this.email=email;
    }
    public String getPassWordHash()
    {
        return passWordHash;
    }
    public void setPassWordHash(String passWordHash)
    {
        this.passWordHash=passWordHash;
    }
    public UserStatus getStatus()
    {
        return status;
    }
    public void setStatus(UserStatus status)
    {
        this.status=status;
    }
    public Instant getCreatedAt()

    {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt)
    {
        this.createdAt=createdAt;
    }

    public Instant getLastLoginAt()
    {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt)
    {
        this.lastLoginAt=lastLoginAt;
    }
}
