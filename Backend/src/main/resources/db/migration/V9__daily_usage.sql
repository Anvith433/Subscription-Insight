-- V9: Record usage one day at a time, and build each month out of days.
--
-- WHY A SINGLE SCAN CANNOT DESCRIBE A MONTH
-- Android keeps the detailed usage event log -- the only source that can say how long
-- an app was on screen between two exact times -- for ten days. UsageStatsDatabase
-- prunes INTERVAL_DAILY files after 10 days (addDays(-10) in prune(), AOSP
-- android14-release), and queryEvents() reads from exactly those files. So a scan taken
-- on the 25th can see the 15th onwards and nothing earlier, however it is asked.
--
-- The previous client library made this worse by asking for its "month" as
-- queryEvents(INTERVAL_MONTHLY, now): INTERVAL_MONTHLY is the constant 2, so the start
-- time was 2 ms after 1970 and the "month" was whatever the phone still remembered --
-- too little late in the month, and last month's days early in it. The server filed it
-- as a complete month either way.
--
-- WHAT CHANGES
-- The app now reports minutes per calendar day for every day it can actually see. Each
-- sync covers roughly the last ten days, so syncing at least weekly leaves no gaps. A
-- month's user_snapshots row is rebuilt from these rows: usage_count is their sum and
-- observed_days is how many days of the month have a row, so a month with gaps is read
-- by the engine as partial (a lower bound) instead of as complete.
--
-- Zero is a real value here. A row with minutes = 0 means "this day was scanned and the
-- app was not used", which is different from a day with no row at all ("not scanned").

CREATE TABLE usage_days (
    id              BIGINT    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subscription_id BIGINT    NOT NULL,
    usage_date      DATE      NOT NULL,
    minutes         INT       NOT NULL,
    updated_at      TIMESTAMP NOT NULL,

    CONSTRAINT uk_usage_days_subscription_date UNIQUE (subscription_id, usage_date),

    CONSTRAINT fk_usage_days_subscription
        FOREIGN KEY (subscription_id) REFERENCES subscriptions(id),

    -- A day has 1440 minutes. Anything outside that is not a measurement.
    CONSTRAINT ck_usage_days_minutes CHECK (minutes BETWEEN 0 AND 1440)
);
