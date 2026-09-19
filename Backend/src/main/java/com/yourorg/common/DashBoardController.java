package com.yourorg.common;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yourorg.Users.CurrentUserService;

/**
 * The screen the product is really about: what you pay for, what you use, and what
 * you should do about it.
 *
 * <p>{@code GET /api/dashboard/{userId}} still returns a JSON array of one row per
 * subscription, so the existing Android client keeps working unchanged. The
 * account-level analytics are a separate endpoint rather than a wrapper object, for
 * the same reason.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashBoardController {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private CurrentUserService currentUser;

    /**
     * @param period optional month as {@code YYYY-MM}. Omitted or unrecognised -- which
     *               includes the legacy {@code TODAY}/{@code WEEK}/{@code MONTH} values
     *               -- falls back to the most recent month with usage data. The
     *               response states the period it describes in every row.
     */
    @GetMapping("/{userId}")
    public List<DashBoardResponseDTO> getDashboardData(
            @PathVariable Long userId,
            @RequestParam(name = "period", required = false) String period) {
        Long ownId = currentUser.requireOwnership(userId);
        return dashboardService.getDashboardData(ownId, period);
    }

    /** Totals: monthly and yearly spend, unused count, and recoverable money. */
    @GetMapping("/{userId}/summary")
    public DashboardSummaryDTO getSummary(
            @PathVariable Long userId,
            @RequestParam(name = "period", required = false) String period) {
        Long ownId = currentUser.requireOwnership(userId);
        return dashboardService.getSummary(ownId, period);
    }
}
