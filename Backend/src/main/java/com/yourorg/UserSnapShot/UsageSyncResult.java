package com.yourorg.UserSnapShot;

import java.util.List;

/**
 * Outcome of a usage sync. Replaces the previous empty 200/500 response so the
 * client (and anyone debugging) can see which apps were matched to a subscription
 * and which were ignored, instead of guessing why nothing changed.
 *
 * @param period   the month the usage was recorded against, YYYY-MM
 * @param synced   provider names whose usage was stored
 * @param skipped  identifiers of reported apps that are not subscriptions for this user
 * @param rejected provider names whose figures could not be true -- a date more than a
 *                 day ahead, more than 24 hours in a day, more minutes than the scan
 *                 covered -- and were not stored
 */
public record UsageSyncResult(
        String period,
        List<String> synced,
        List<String> skipped,
        List<String> rejected
) {
}
