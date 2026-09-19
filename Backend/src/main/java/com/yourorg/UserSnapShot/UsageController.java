package com.yourorg.UserSnapShot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.yourorg.Users.CurrentUserService;

/**
 * Receives app-usage figures measured on the device.
 *
 * <p>Three changes from the previous version:
 *
 * <ul>
 *   <li>It delegates to {@link UserSnapShotService}, the author's own service, which
 *       matches on package name, merges repeated syncs correctly and triggers billing
 *       and recommendations. The old path called
 *       {@code SubscriptionService.syncUsageAndBilling}, which despite its name did
 *       no billing and wrote a 10-character date into a 7-character column.</li>
 *   <li>The user id comes from the token, not the request body. Anyone could
 *       previously post usage into any account by changing a number in the JSON.</li>
 *   <li>It reports which apps matched a subscription and which were ignored. The old
 *       handler answered "Usage synced successfully" even when it matched nothing,
 *       and on failure returned {@code e.getMessage()} -- leaking internals to the
 *       client and reporting a mismatched app name as a server error.</li>
 * </ul>
 */
@RestController
public class UsageController {

    @Autowired
    private UserSnapShotService userSnapShotService;

    @Autowired
    private CurrentUserService currentUser;

    @PutMapping("/usage")
    public ResponseEntity<?> updateUsage(@RequestBody UsageRequest request) {
        if (request == null || request.getApps() == null || request.getApps().isEmpty()) {
            return ResponseEntity.badRequest().body("No app usage was supplied.");
        }
        Long userId = currentUser.requireId();
        // request.getPeriod() names the span the device scanned ("TODAY"/"WEEK"/
        // "MONTH"). It used to be accepted and discarded, which is how a one-day scan
        // came to be stored as a whole month; it is now passed through and recorded.
        // An absent or unrecognised value is handled by UsageWindow.fromClient, so an
        // older client is not rejected here.
        return ResponseEntity.ok(userSnapShotService.processUsage(
                userId, request.getPeriod(), request.getApps()));
    }
}
