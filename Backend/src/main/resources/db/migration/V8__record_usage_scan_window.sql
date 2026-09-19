-- V8: Record how much of the month a usage scan actually covered.
--
-- The Android client has always sent a `period` field of "TODAY" / "WEEK" / "MONTH",
-- and the server has always thrown it away: UserSnapShotService.processUsage called
-- YearMonth.now() unconditionally. Every scan was therefore filed as if it described
-- the whole month. In a fresh month, with no row to max() against, a "Today" scan
-- wrote one day's minutes as the month's usage -- 20 minutes of a 199/month plan
-- became 597 per hour and the engine returned CANCEL at 0.85 confidence off a single
-- day. The engine's existing partial-data guard keys on subscriptions.start_date, so
-- it did not catch this.
--
-- WHY THESE ARE STORED RATHER THAN DERIVED
-- DashboardService recomputes recommendations on *read*, not only after a sync. A
-- coverage figure derived from the read-time date would describe when the dashboard
-- was opened, not when the scan was taken -- so a Today scan on the 3rd would read
-- back as a full month on the 25th, and the dashboard would reach a different
-- verdict than the sync did. The three columns below pin the observation to the
-- moment it was made.
--
-- WHY NULLABLE, AND WHY NOT BACKFILLED
-- Rows written before this migration -- the V2 seed, plus any real synced data --
-- have no window recorded. NULL is read as "unknown, assume complete", which
-- preserves the previous behaviour exactly. Backfilling 'MONTH' would be worse than
-- leaving it NULL: it would assert a measurement that was never made. Treating NULL
-- as partial would be worse still, flipping every existing row to a provisional
-- CONSIDER and breaking the seeded demo.

ALTER TABLE user_snapshots
    ADD COLUMN observed_window     VARCHAR(10) NULL AFTER usage_count,
    ADD COLUMN observed_days       INT         NULL AFTER observed_window,
    ADD COLUMN month_days_elapsed  INT         NULL AFTER observed_days;

-- The three columns describe one observation and are only meaningful together, so
-- they are constrained together: either all NULL (a pre-V8 row) or all present.
-- observed_days <= month_days_elapsed is what makes "partial" decidable at all --
-- the engine treats observed_days = month_days_elapsed as a complete month, and a
-- value above the denominator would be a coverage ratio over 1.
--
-- The enum comparison casts to BINARY, following V7: the default
-- utf8mb4_0900_ai_ci collation is case-insensitive, so without the cast this
-- constraint would happily accept 'Month' -- which Hibernate then fails to read
-- back with "No enum constant ... Month", exactly the V6/V7 bug in a new column.
ALTER TABLE user_snapshots ADD CONSTRAINT ck_user_snapshots_observed_window CHECK (
    (observed_window IS NULL AND observed_days IS NULL AND month_days_elapsed IS NULL)
    OR (
        CAST(observed_window AS BINARY) IN (
            CAST('DAY' AS BINARY), CAST('WEEK' AS BINARY), CAST('MONTH' AS BINARY)
        )
        AND observed_days      >= 1
        AND month_days_elapsed >= 1
        AND observed_days <= month_days_elapsed
    )
);
