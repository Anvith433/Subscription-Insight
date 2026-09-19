
INSERT IGNORE INTO users (id, username, email, passwordhash, status, created_at)
VALUES
(1, 'anvith', 'anvith@gmail.com', 'hashed_password', 'ACTIVE', NOW()),
(2, 'rahul', 'rahul@gmail.com', 'hashed_password', 'ACTIVE', NOW());

INSERT IGNORE INTO subscriptions (id, user_id, provider_name, category, start_date, renewal_cycle, renewal_date, price, currency, status)
VALUES
(1, 1, 'Spotify', 'Music', '2025-01-01', 'MONTHLY', '2026-04-01', 119.00, 'INR', 'ACTIVE'),
(2, 1, 'YouTube Premium', 'Video', '2025-02-01', 'MONTHLY', '2026-04-01', 129.00, 'INR', 'ACTIVE'),
(3, 2, 'Netflix', 'Video', '2025-01-15', 'MONTHLY', '2026-04-15', 199.00, 'INR', 'ACTIVE');


INSERT IGNORE INTO recommendations (user_id, subscription_id, type, reason, confidence_score, status, generated_at)
VALUES
(1, 1, 'KEEP', 'High usage detected', 0.95, 'ACTIVE', NOW()),
(1, 2, 'KEEP', 'Moderate usage', 0.80, 'ACTIVE', NOW()),
(2, 3, 'CANCEL', 'Low usage detected', 0.70, 'ACTIVE', NOW());


INSERT IGNORE INTO billing_records (subscription_id, amount, currency, billing_period, paid_at, payment_method, source, created_at)
VALUES
(1, 119.00, 'INR', '2026-03', NOW(), 'UPI', 'SYSTEM', NOW()),
(2, 129.00, 'INR', '2026-03', NOW(), 'CARD', 'SYSTEM', NOW()),
(3, 199.00, 'INR', '2026-03', NOW(), 'UPI', 'SYSTEM', NOW());


INSERT IGNORE INTO user_snapshots (subscription_id, period, usage_count, source, created_at)
VALUES
(1, '2026-03', 180, 'ANDROID', NOW()),
(2, '2026-03', 90, 'ANDROID', NOW()),
(3, '2026-03', 30, 'ANDROID', NOW());
