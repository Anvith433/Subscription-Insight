-- V7: Actually apply the enum-case repair that V6 intended, and stop the same
-- mistake reaching new rows.
--
-- V6 wrote:
--     UPDATE subscriptions SET category = UPPER(category)
--      WHERE category <> UPPER(category);
--
-- On MySQL that guard never matches. The default collation, utf8mb4_0900_ai_ci, is
-- case-insensitive, so 'Music' <> 'MUSIC' evaluates to FALSE and zero rows were
-- updated -- while the migration reported success. Verified directly:
--     SELECT 'Music' <> UPPER('Music');  -->  0
--
-- The consequence was not cosmetic. Hibernate maps Category with
-- @Enumerated(EnumType.STRING), so reading a row whose category is 'Video' calls
-- Enum.valueOf(Category.class, "Video") and throws:
--     No enum constant com.yourorg.Subscriptions.Category.Video
-- Every dashboard request for a seeded user returned HTTP 500.
--
-- V6 is left untouched: it has already been applied, so editing it would change its
-- recorded checksum and fail validation on any existing database.

-- ---------------------------------------------------------------------------
-- 1. Unconditional update. No WHERE clause, so no collation can skip it. Running
--    UPPER() over an already-uppercase value is a no-op, which makes this safe to
--    re-run and safe on databases where V6 did work.
-- ---------------------------------------------------------------------------
UPDATE subscriptions SET category = UPPER(category);

-- Same latent problem in the other enum-backed columns on this table. None are known
-- to hold mixed-case values today, but the seed file proves the mistake is easy to
-- make, and normalising costs nothing.
UPDATE subscriptions SET renewal_cycle = UPPER(renewal_cycle);
UPDATE subscriptions SET status        = UPPER(status);

-- And the enum-backed columns elsewhere, for the same reason.
UPDATE users             SET status          = UPPER(status);
UPDATE user_snapshots    SET source          = UPPER(source);
UPDATE recommendations   SET type            = UPPER(type);
UPDATE recommendations   SET status          = UPPER(status);
UPDATE billing_records   SET payment_method  = UPPER(payment_method);
UPDATE billing_records   SET source          = UPPER(source);

-- ---------------------------------------------------------------------------
-- 2. Stop it recurring. A CHECK constraint rejects any future value that is not one
--    of the Java enum's members, so a bad INSERT fails loudly at write time instead
--    of surfacing as a 500 on read, days later, in a different part of the system.
--
--    The comparison is against BINARY values so the constraint is genuinely
--    case-sensitive -- without the cast, the case-insensitive collation would accept
--    'Music' as matching 'MUSIC' and the constraint would not do its job.
-- ---------------------------------------------------------------------------
ALTER TABLE subscriptions ADD CONSTRAINT ck_subscriptions_category CHECK (
    CAST(category AS BINARY) IN (
        CAST('OTT' AS BINARY), CAST('CLOUD' AS BINARY), CAST('VIDEO' AS BINARY),
        CAST('MUSIC' AS BINARY), CAST('UTILITIES' AS BINARY), CAST('EDUCATION' AS BINARY),
        CAST('FINANCE' AS BINARY), CAST('SOCIAL' AS BINARY), CAST('SHOPPING' AS BINARY),
        CAST('UTILITY' AS BINARY), CAST('SYSTEM' AS BINARY), CAST('DEVELOPMENT' AS BINARY)
    )
);

ALTER TABLE subscriptions ADD CONSTRAINT ck_subscriptions_renewal_cycle CHECK (
    CAST(renewal_cycle AS BINARY) IN (
        CAST('FREE' AS BINARY), CAST('MONTHLY' AS BINARY), CAST('YEARLY' AS BINARY)
    )
);

ALTER TABLE subscriptions ADD CONSTRAINT ck_subscriptions_status CHECK (
    CAST(status AS BINARY) IN (
        CAST('ACTIVE' AS BINARY), CAST('CANCELLED' AS BINARY), CAST('PAUSED' AS BINARY)
    )
);

ALTER TABLE recommendations ADD CONSTRAINT ck_recommendations_type CHECK (
    CAST(type AS BINARY) IN (
        CAST('KEEP' AS BINARY), CAST('CANCEL' AS BINARY), CAST('CONSIDER' AS BINARY),
        CAST('DOWNGRADE' AS BINARY), CAST('UPGRADE' AS BINARY)
    )
);
