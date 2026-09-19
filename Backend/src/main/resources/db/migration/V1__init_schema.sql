CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    passwordhash VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_login_at TIMESTAMP NULL
);
CREATE INDEX idx_users_email ON users(email);

CREATE TABLE subscriptions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider_name VARCHAR(255) NOT NULL,
    category VARCHAR(50) NOT NULL,
    start_date DATE NOT NULL,
    renewal_cycle VARCHAR(20) NOT NULL,
    renewal_date DATE NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL,
    CONSTRAINT fk_subscription_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE
);

CREATE TABLE recommendations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    subscription_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    reason VARCHAR(100),
    confidence_score DECIMAL(5,2),
    status VARCHAR(50) NOT NULL,
    generated_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_recommendation_user
        FOREIGN KEY (user_id) REFERENCES users(id),

    CONSTRAINT fk_recommendation_subscription
        FOREIGN KEY (subscription_id) REFERENCES subscriptions(id)
);

CREATE TABLE billing_records (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subscription_id BIGINT NOT NULL,
    amount DECIMAL(10,2),
    currency VARCHAR(3) NOT NULL,
    billing_period VARCHAR(7) NOT NULL,
    paid_at TIMESTAMP NOT NULL,
    payment_method VARCHAR(50) NOT NULL,
    source VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_billing_subscription
        FOREIGN KEY (subscription_id) REFERENCES subscriptions(id)
);

CREATE TABLE user_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subscription_id BIGINT NOT NULL,
    period VARCHAR(7) NOT NULL,
    usage_count INTEGER NOT NULL,
    last_used_at TIMESTAMP NULL,
    source VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,

    CONSTRAINT uk_subscription_period
        UNIQUE (subscription_id, period),

    CONSTRAINT fk_snapshot_subscription
        FOREIGN KEY (subscription_id) REFERENCES subscriptions(id)
);
