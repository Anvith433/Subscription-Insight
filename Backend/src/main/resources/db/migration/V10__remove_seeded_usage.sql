-- V10: Remove the invented usage and payments that V2 seeded.
--
-- V2__seed_data.sql inserted three user_snapshots rows (180, 90 and 30 minutes for
-- 2026-03) labelled source = 'ANDROID', though no device ever measured them, and three
-- billing_records claiming specific UPI and card payments that never happened. Every
-- verdict for the demo accounts was computed from those numbers.
--
-- V2 cannot be edited (Flyway would reject its changed checksum on any database that
-- has already run it), so the rows are removed here. The demo users and their
-- subscriptions stay: they are configuration, not a claim about anyone's behaviour.
--
-- Each DELETE matches the seeded rows exactly -- ids, values, and the columns only the
-- seed left empty -- so a real row is never caught by it:
--   * a real sync after V8 always records observed_window, which the seed rows lack;
--   * the server generates bills with payment_method 'AUTO_SYNC', never UPI or CARD.

DELETE snap
  FROM user_snapshots snap
  JOIN subscriptions sub ON sub.id = snap.subscription_id
 WHERE snap.period = '2026-03'
   AND snap.observed_window IS NULL
   AND snap.source = 'ANDROID'
   AND (   (sub.id = 1 AND sub.user_id = 1 AND sub.provider_name = 'Spotify'         AND snap.usage_count = 180)
        OR (sub.id = 2 AND sub.user_id = 1 AND sub.provider_name = 'YouTube Premium' AND snap.usage_count = 90)
        OR (sub.id = 3 AND sub.user_id = 2 AND sub.provider_name = 'Netflix'         AND snap.usage_count = 30));

DELETE FROM billing_records
 WHERE subscription_id IN (1, 2, 3)
   AND billing_period = '2026-03'
   AND source = 'SYSTEM'
   AND payment_method IN ('UPI', 'CARD');

-- The seeded verdicts quoted the invented usage ("High usage detected"). Ones the
-- engine has since recomputed carry its own wording and are left for it to refresh.
DELETE FROM recommendations
 WHERE subscription_id IN (1, 2, 3)
   AND reason IN ('High usage detected', 'Moderate usage', 'Low usage detected');
