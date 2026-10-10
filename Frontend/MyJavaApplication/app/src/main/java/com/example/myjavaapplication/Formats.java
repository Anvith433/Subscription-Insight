package com.example.myjavaapplication;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Turns the server's values into what a person would write: "₹1,046" rather than
 * "INR 1046.00", "1h 12m" rather than "72", "October 2026" rather than "2026-10".
 *
 * <p>Every method here is display-only and forgiving: a value it cannot parse comes back
 * as given rather than throwing, because a slightly raw label beats a crashed screen.
 *
 * <p>No {@code java.time}: minSdk 24 without desugaring (see AddSubscriptionActivity).
 */
final class Formats {

    private static final BigDecimal HUNDRED = new BigDecimal(100);

    private Formats() {
    }

    /** "₹199", "₹129.50", "$9.99", or "AED 30" for a currency without a familiar sign. */
    static String money(String currency, BigDecimal amount) {
        if (amount == null) {
            return "—";
        }
        // Whole amounts lose the ".00"; anything with paise keeps both digits, since
        // "₹129.5" reads as a typo.
        boolean whole = amount.signum() == 0 || amount.stripTrailingZeros().scale() <= 0;
        return symbol(currency) + decimal(whole ? "#,##0" : "#,##0.00").format(amount);
    }

    static String money(String currency, Double amount) {
        return amount == null ? "—" : money(currency, BigDecimal.valueOf(amount));
    }

    /**
     * A derived rate like cost per hour. Unlike a price it is an estimate, so past ₹100
     * the paise are noise and are rounded away.
     */
    static String rate(String currency, Double amount) {
        if (amount == null) {
            return "—";
        }
        BigDecimal value = BigDecimal.valueOf(amount);
        if (value.abs().compareTo(HUNDRED) >= 0) {
            value = value.setScale(0, RoundingMode.HALF_UP);
        } else {
            value = value.setScale(2, RoundingMode.HALF_UP);
        }
        return money(currency, value);
    }

    /** "45m", "2h", "1h 12m". */
    static String minutes(long minutes) {
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
    }

    /** "2026-11-03" becomes "3 Nov 2026". */
    static String date(String isoDate) {
        return reformat(isoDate, "yyyy-MM-dd", "d MMM yyyy");
    }

    /** Like {@link #date}, but drops the year when it is this one: "3 Nov". */
    static String shortDate(String isoDate) {
        String thisYear = new SimpleDateFormat("yyyy", Locale.ROOT).format(new Date());
        boolean sameYear = isoDate != null && isoDate.startsWith(thisYear + "-");
        return reformat(isoDate, "yyyy-MM-dd", sameYear ? "d MMM" : "d MMM yyyy");
    }

    /** "2026-10" becomes "October 2026". */
    static String month(String isoMonth) {
        return reformat(isoMonth, "yyyy-MM", "MMMM yyyy");
    }

    /** The server's enum names, for reading: "MONTHLY" is "Monthly"; "OTT" stays "OTT". */
    static String enumLabel(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 3) {
            return value.toUpperCase(Locale.ROOT);
        }
        String lower = value.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** The inverse of {@link #enumLabel}: "Monthly" back to "MONTHLY". */
    static String enumValue(String label) {
        return label == null ? "" : label.trim().replace(' ', '_').toUpperCase(Locale.ROOT);
    }

    private static String symbol(String currency) {
        if (currency == null || currency.trim().isEmpty()) {
            return "";
        }
        switch (currency.trim().toUpperCase(Locale.ROOT)) {
            case "INR":
                return "₹";
            case "USD":
                return "$";
            case "EUR":
                return "€";
            case "GBP":
                return "£";
            case "JPY":
                return "¥";
            default:
                return currency.trim().toUpperCase(Locale.ROOT) + " ";
        }
    }

    private static DecimalFormat decimal(String pattern) {
        DecimalFormat format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    }

    private static String reformat(String value, String from, String to) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            SimpleDateFormat in = new SimpleDateFormat(from, Locale.ROOT);
            in.setLenient(false);
            Date parsed = in.parse(value);
            return parsed == null ? value : new SimpleDateFormat(to, Locale.getDefault()).format(parsed);
        } catch (ParseException e) {
            return value;
        }
    }
}
