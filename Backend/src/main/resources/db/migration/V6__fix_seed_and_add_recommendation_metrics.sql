-- V6: Repair data defects introduced by V1/V2 and extend `recommendations`
-- so that every recommendation is explainable (metrics are stored, not recomputed
-- on the fly and then thrown away).
--
-- NOTE: V1..V5 are intentionally left untouched. They have already been applied
-- to existing databases, so editing them would change their Flyway checksums.
-- Every correction below is therefore expressed as a forward migration.

-- ---------------------------------------------------------------------------
-- 1. Fix enum case mismatch in seeded subscriptions.
--    V2 inserted category values 'Music' / 'Video', but the Java enum
--    com.yourorg.Subscriptions.Category declares MUSIC / VIDEO. Because the
--    field is mapped with @Enumerated(EnumType.STRING), Hibernate throws
--    "No enum constant ...Category.Music" when loading those rows, which made
--    every read of a seeded subscription fail.
-- ---------------------------------------------------------------------------
UPDATE subscriptions SET category = UPPER(category) WHERE category <> UPPER(category);

-- ---------------------------------------------------------------------------
-- 2. package_name is the key used to match an Android app to a subscription.
--    V3 added it as NULLable, but the entity declares nullable = false. Backfill
--    any gaps deterministically from provider_name, then enforce NOT NULL so the
--    schema and the entity agree.
-- ---------------------------------------------------------------------------
UPDATE subscriptions
   SET package_name = CONCAT('unknown.', LOWER(REPLACE(provider_name, ' ', '')))
 WHERE package_name IS NULL OR package_name = '';

ALTER TABLE subscriptions
MODIFY COLUMN package_name VARCHAR(255) NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Indexes / integrity constraints for the hot query paths.
-- ---------------------------------------------------------------------------
-- Every dashboard and sync request starts with "find this user's subscriptions".
CREATE INDEX idx_subscriptions_user ON subscriptions(user_id);

-- Makes bill generation idempotent: one bill per subscription per billing period.
ALTER TABLE billing_records
ADD CONSTRAINT uk_billing_subscription_period UNIQUE (subscription_id, billing_period);

-- ---------------------------------------------------------------------------
-- 4. Recommendations: store the evidence behind each verdict.
--    reason was VARCHAR(100), which is too small for a human-readable
--    explanation (e.g. cost per hour + trend + comparison).
-- ---------------------------------------------------------------------------
ALTER TABLE recommendations
MODIFY COLUMN reason VARCHAR(500);

ALTER TABLE recommendations
    ADD COLUMN period VARCHAR(7) NULL                        AFTER subscription_id,
    ADD COLUMN usage_minutes INT NULL                        AFTER reason,
    ADD COLUMN monthly_cost DECIMAL(10,2) NULL               AFTER usage_minutes,
    ADD COLUMN cost_per_hour DECIMAL(12,2) NULL              AFTER monthly_cost,
    ADD COLUMN value_score DECIMAL(5,2) NULL                 AFTER cost_per_hour,
    ADD COLUMN estimated_monthly_savings DECIMAL(10,2) NULL  AFTER value_score;

-- One current recommendation per (user, subscription). The existing
-- RecommendationRepository.findByUserIdAndSubscriptionId returns an Optional, so
-- duplicates would raise NonUniqueResultException; the engine upserts instead.
ALTER TABLE recommendations
ADD CONSTRAINT uk_recommendation_user_subscription UNIQUE (user_id, subscription_id);
