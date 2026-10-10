package com.example.myjavaapplication;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText etEmail, etPassword, etActualUsername, etConfirmPassword;
    private TextInputLayout tilActualUsername, tilConfirmPassword, tilEmail;
    private MaterialButton btnLogin;
    // The four setup steps are cards rather than buttons; all they need is a click.
    private View btnPermission, btnSubscriptions, btnSync, btnDashboard;
    private View layoutPostLogin, ivStep1Done;
    private TextView tvSignupLink, tvTitle, tvSubtitle, tvGreeting, tvStep1Number, tvStep1Body;
    private boolean isLoggedIn = false;
    private boolean isSignupMode = false;
    private ApiService apiService;
    // Set from the login response. Deliberately null until then: the previous default
    // of 1L meant every install silently operated on user 1's subscriptions, and a
    // wrong-but-plausible id is worse than no id, because nothing ever fails visibly.
    private Long loggedInUserId = null;
    private String jwtToken = "";

    // What to report to the server, refreshed from GET /api/subscriptions immediately
    // before each scan. This replaced a hardcoded list of nine provider names, which
    // silently dropped every other app on the device -- so a subscription for anything
    // outside those nine could never sync, however correctly the server matched it.
    // Deriving the filter from the user's own subscriptions is also the more private
    // arrangement: only packages the user has told us about ever leave the device.
    // Keyed lowercased, because both server-side lookups are case-insensitive; the
    // package map keeps the name as stored, which is what PackageManager is asked about.
    private final Map<String, String> trackedPackages = new HashMap<>();
    private final Set<String> trackedProviderNames = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        apiService = RetrofitClient.getClient().create(ApiService.class);

        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvGreeting = findViewById(R.id.tvGreeting);
        etActualUsername = findViewById(R.id.etActualUsername);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        tilActualUsername = findViewById(R.id.tilActualUsername);
        tilEmail = findViewById(R.id.tilEmail);
        tilConfirmPassword = findViewById(R.id.tilConfirmPassword);
        btnLogin = findViewById(R.id.btnLogin);
        tvSignupLink = findViewById(R.id.tvSignupLink);
        layoutPostLogin = findViewById(R.id.layoutPostLogin);
        btnPermission = findViewById(R.id.btnPermission);
        btnSubscriptions = findViewById(R.id.btnSubscriptions);
        btnSync = findViewById(R.id.btnSync);
        btnDashboard = findViewById(R.id.btnDashboard);
        tvStep1Number = findViewById(R.id.tvStep1Number);
        tvStep1Body = findViewById(R.id.tvStep1Body);
        ivStep1Done = findViewById(R.id.ivStep1Done);

        tilActualUsername.setVisibility(View.GONE);
        tilConfirmPassword.setVisibility(View.GONE);
        layoutPostLogin.setVisibility(View.GONE);
        tvSignupLink.setText(linkText("New here?", "Create an account"));

        // One link, both directions: it used to vanish in sign-up mode, which left no
        // way back to signing in short of a successful sign-up.
        tvSignupLink.setOnClickListener(v -> {
            if (isSignupMode) switchToLoginMode();
            else switchToSignupMode();
        });

        btnLogin.setOnClickListener(v -> {
            hideKeyboard();
            if (isSignupMode) performSignup();
            else performLogin();
        });

        btnPermission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));

        btnSubscriptions.setOnClickListener(v -> {
            if (!isLoggedIn || loggedInUserId == null) {
                Toast.makeText(this, "Please Login First!", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, SubscriptionsActivity.class);
            intent.putExtra("USER_ID", loggedInUserId);
            intent.putExtra("JWT_TOKEN", jwtToken);
            startActivity(intent);
        });

        btnSync.setOnClickListener(v -> {
            if (checkUsagePermission()) {
                loadSubscriptionsThenScan();
            } else {
                showPermissionDeniedDialog();
            }
        });

        btnDashboard.setOnClickListener(v -> {
            if (!isLoggedIn || loggedInUserId == null) {
                Toast.makeText(this, "Please Login First!", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, DashboardActivity.class);
            intent.putExtra("USER_ID", loggedInUserId);
            intent.putExtra("JWT_TOKEN", jwtToken);
            startActivity(intent);
        });
    }

    /**
     * Coming back from the usage-access settings screen is the usual way into this, and
     * step 1 should show what the user just did there.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (isLoggedIn) {
            updatePermissionStep();
        }
    }

    /**
     * Fetches the user's subscriptions, then reads the phone's usage log.
     *
     * <p>The order matters and is not incidental: {@link #sendUsage} needs the
     * subscription sets to decide what to report, so the fetch has to have completed
     * first. Kicking off both at once would race.
     *
     * <p>There is no period to choose. Each sync reports every day the phone can still
     * account for -- about the last ten -- and the server builds months out of days, so
     * syncing at least weekly covers a month with no gaps.
     *
     * <p>If the fetch fails, the sync is abandoned. It deliberately does not fall back
     * to reporting every installed app and letting the server sort them out: that would
     * send the user's entire app-usage profile over the wire -- in cleartext, on this
     * build -- to work around a matching problem.
     */
    private void loadSubscriptionsThenScan() {
        if (!isLoggedIn || loggedInUserId == null) {
            Toast.makeText(this, "Please Login First!", Toast.LENGTH_SHORT).show();
            return;
        }

        apiService.getSubscriptions("Bearer " + jwtToken).enqueue(new Callback<List<SubscriptionResponse>>() {
            @Override
            public void onResponse(Call<List<SubscriptionResponse>> call,
                                   Response<List<SubscriptionResponse>> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    Toast.makeText(MainActivity.this,
                            "Couldn't load your subscriptions (" + response.code()
                                    + "); sync cancelled.",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                List<SubscriptionResponse> subs = response.body();
                if (subs.isEmpty()) {
                    // Scanning now would report nothing and read as a broken sync.
                    Toast.makeText(MainActivity.this,
                            "No subscriptions yet. Add one in step 2, then sync.",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                trackedPackages.clear();
                trackedProviderNames.clear();
                for (SubscriptionResponse sub : subs) {
                    String pkg = sub.getPackageName();
                    if (pkg != null && !pkg.trim().isEmpty()) {
                        trackedPackages.put(pkg.trim().toLowerCase(Locale.ROOT), pkg.trim());
                    }
                    String provider = sub.getProviderName();
                    if (provider != null && !provider.trim().isEmpty()) {
                        trackedProviderNames.add(provider.trim().toLowerCase(Locale.ROOT));
                    }
                }
                Log.i("USAGE_SYNC", "Filtering against " + trackedPackages.size() + " package(s) and "
                        + trackedProviderNames.size() + " provider name(s)");

                Toast.makeText(MainActivity.this, "Reading usage on this phone...", Toast.LENGTH_SHORT).show();
                // Walking ten days of the usage log is slow enough to stall the screen, so
                // it runs off the main thread, as the app list in AddSubscriptionActivity does.
                final Context appContext = getApplicationContext();
                new Thread(() -> {
                    UsageReader.Report read;
                    try {
                        read = UsageReader.read(appContext);
                    } catch (RuntimeException e) {
                        // Without this a failure would end the thread silently and the
                        // user would wait for a result that never comes.
                        Log.e("USAGE_SYNC", "Could not read usage: " + e.getMessage());
                        read = null;
                    }
                    final UsageReader.Report report = read;
                    runOnUiThread(() -> {
                        if (isFinishing()) {
                            return;
                        }
                        if (report == null) {
                            Toast.makeText(MainActivity.this, "Couldn't read usage on this phone; sync cancelled.",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            sendUsage(report);
                        }
                    });
                }).start();
            }

            @Override
            public void onFailure(Call<List<SubscriptionResponse>> call, Throwable t) {
                Toast.makeText(MainActivity.this,
                        "Couldn't load your subscriptions; sync cancelled.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Sends, for each subscription whose app is on this phone, its minutes on every day
     * the scan covered.
     *
     * <p>Days with no use are sent as zeros. "Installed and not opened all week" is
     * exactly what a verdict needs to know; leaving those days out made an unused
     * subscription read as "no data" on the server rather than as unused. An app that
     * is not installed is not sent at all: this phone can say nothing about it.
     *
     * <p>Matching mirrors the server's own two tiers: package name first, because it is
     * a stable OS identifier, then the app's label for subscriptions whose package name
     * is unset. The label is sent as the device shows it, never rewritten to a provider
     * name, so the two tiers stay independent.
     */
    private void sendUsage(UsageReader.Report report) {
        if (!isLoggedIn || loggedInUserId == null) {
            Toast.makeText(this, "Please Login First!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (report.isEmpty()) {
            Toast.makeText(this, "Android hasn't recorded any usage to read yet. Try again later.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        PackageManager pm = getPackageManager();
        Map<String, String> usedByKey = new HashMap<>();
        for (String pkg : report.usedPackages()) {
            usedByKey.put(pkg.toLowerCase(Locale.ROOT), pkg);
        }

        // package -> label, in the order they will be sent
        Map<String, String> toReport = new LinkedHashMap<>();
        for (Map.Entry<String, String> tracked : trackedPackages.entrySet()) {
            String pkg = usedByKey.containsKey(tracked.getKey()) ? usedByKey.get(tracked.getKey()) : tracked.getValue();
            String label = installedLabel(pm, pkg);
            if (label != null) {
                toReport.put(pkg, label);
            }
        }
        for (String pkg : report.usedPackages()) {
            if (toReport.containsKey(pkg) || trackedPackages.containsKey(pkg.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String label = installedLabel(pm, pkg);
            if (label != null && trackedProviderNames.contains(label.trim().toLowerCase(Locale.ROOT))) {
                toReport.put(pkg, label);
            }
        }

        if (toReport.isEmpty()) {
            Toast.makeText(this, "None of your subscriptions' apps are installed on this phone, "
                    + "so there is nothing here to measure.", Toast.LENGTH_LONG).show();
            return;
        }

        List<AppUsageInfo> apps = new ArrayList<>();
        for (Map.Entry<String, String> app : toReport.entrySet()) {
            List<AppUsageInfo.Day> days = new ArrayList<>();
            for (String day : report.days) {
                days.add(new AppUsageInfo.Day(day, report.minutes(app.getKey(), day)));
            }
            AppUsageInfo info = new AppUsageInfo(app.getKey(), app.getValue(), days);
            apps.add(info);
            Log.i("USAGE_SYNC", "Reporting " + app.getValue() + " (" + app.getKey() + "): "
                    + info.getUsageMinutes() + " min over " + days.size() + " day(s)");
        }

        final int dayCount = report.days.size();
        apiService.syncUsage("Bearer " + jwtToken, new UsageRequest(loggedInUserId, apps))
                .enqueue(new Callback<UsageSyncResponse>() {
            @Override
            public void onResponse(Call<UsageSyncResponse> call, Response<UsageSyncResponse> response) {
                if (!response.isSuccessful()) {
                    Toast.makeText(MainActivity.this, "Sync failed: " + response.code(), Toast.LENGTH_SHORT).show();
                    return;
                }
                UsageSyncResponse body = response.body();
                if (body == null) {
                    Toast.makeText(MainActivity.this, "Synced.", Toast.LENGTH_SHORT).show();
                    return;
                }
                // A 200 with nothing stored is a real failure -- an app whose name
                // matches no subscription, or figures the server refused -- so it is
                // reported as such rather than as unqualified success.
                int synced = body.getSyncedCount();
                String message = synced == 0
                        ? "Nothing synced: none of these apps match a subscription."
                        : "Synced " + synced + (synced == 1 ? " subscription" : " subscriptions")
                                + " across " + (dayCount == 1 ? "today" : dayCount + " days") + ".";
                if (body.getRejectedCount() > 0) {
                    message += " " + body.getRejectedCount() + " had figures the server could not accept.";
                }
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                Log.i("USAGE_SYNC", "synced=" + body.getSynced() + " skipped=" + body.getSkipped()
                        + " rejected=" + body.getRejectedCount());
            }

            @Override
            public void onFailure(Call<UsageSyncResponse> call, Throwable t) {
                Toast.makeText(MainActivity.this, "Laptop unreachable", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** The app's label if it is installed on this phone, otherwise null. */
    private static String installedLabel(PackageManager pm, String packageName) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void performSignup() {
        if (etActualUsername.getText() == null || etEmail.getText() == null || etPassword.getText() == null || etConfirmPassword.getText() == null) return;
        String username = etActualUsername.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        String pass = etPassword.getText().toString().trim();
        String confirm = etConfirmPassword.getText().toString().trim();

        if (username.isEmpty() || email.isEmpty() || pass.isEmpty()) return;
        if (!pass.equals(confirm)) {
            tilConfirmPassword.setError("Mismatch");
            return;
        }

        apiService.signupUser(new SignupRequest(username, email, pass)).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (response.isSuccessful()) {
                    Toast.makeText(MainActivity.this, "Account Created!", Toast.LENGTH_SHORT).show();
                    switchToLoginMode();
                }
            }
            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Toast.makeText(MainActivity.this, "Connection Error", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void performLogin() {
        if (etEmail.getText() == null || etPassword.getText() == null) return;
        String userOrEmail = etEmail.getText().toString().trim();
        String pass = etPassword.getText().toString().trim();
        if (userOrEmail.isEmpty() || pass.isEmpty()) return;

        apiService.loginUser(new LoginRequest(userOrEmail, pass)).enqueue(new Callback<AuthResponse>() {
            @Override
            public void onResponse(Call<AuthResponse> call, Response<AuthResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    AuthResponse body = response.body();
                    if (body.getUserId() == null) {
                        // Without an id the client cannot address its own data, and
                        // guessing one is how the hardcoded 1L happened. Fail visibly.
                        Toast.makeText(MainActivity.this,
                                "Login incomplete: server did not return an account id.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    isLoggedIn = true;
                    jwtToken = body.getToken();
                    loggedInUserId = body.getUserId();
                    handleLoginSuccess(body.getUsername());
                } else {
                    Toast.makeText(MainActivity.this, "Login Failed", Toast.LENGTH_SHORT).show();
                }
            }
            @Override
            public void onFailure(Call<AuthResponse> call, Throwable t) {
                Toast.makeText(MainActivity.this, "Server not reachable", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleLoginSuccess(String user) {
        findViewById(R.id.loginCard).setVisibility(View.GONE);
        layoutPostLogin.setVisibility(View.VISIBLE);
        tvGreeting.setText(user == null || user.trim().isEmpty()
                ? "Welcome back." : "Hi, " + user.trim() + ".");
        updatePermissionStep();
    }

    /** Step 1 swaps its number for a tick once usage access is on. */
    private void updatePermissionStep() {
        boolean granted = checkUsagePermission();
        tvStep1Number.setVisibility(granted ? View.INVISIBLE : View.VISIBLE);
        ivStep1Done.setVisibility(granted ? View.VISIBLE : View.GONE);
        tvStep1Body.setText(granted
                ? "Done. You can turn it off in Settings any time."
                : "So the app can see how long you spend in each app.");
    }

    private void switchToSignupMode() {
        isSignupMode = true;
        tilActualUsername.setVisibility(View.VISIBLE);
        tilConfirmPassword.setVisibility(View.VISIBLE);
        tilEmail.setHint("Email");
        btnLogin.setText("Create account");
        tvTitle.setText("Let's get you set up.");
        tvSubtitle.setText("One account keeps your subscriptions and their usage together.");
        tvSignupLink.setText(linkText("Already have an account?", "Sign in"));
    }

    private void switchToLoginMode() {
        isSignupMode = false;
        tilActualUsername.setVisibility(View.GONE);
        tilConfirmPassword.setVisibility(View.GONE);
        tilConfirmPassword.setError(null);
        tilEmail.setHint("Email or username");
        btnLogin.setText("Sign in");
        tvTitle.setText("Is that ₹199 a month actually worth it?");
        tvSubtitle.setText("Line up what you pay for each subscription against how much you actually use it.");
        tvSignupLink.setText(linkText("New here?", "Create an account"));
    }

    /** "New here? Create an account", with the action half in ink and bold. */
    private CharSequence linkText(String lead, String action) {
        SpannableString text = new SpannableString(lead + " " + action);
        int start = lead.length() + 1;
        text.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.ink)),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private void hideKeyboard() {
        View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private boolean checkUsagePermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void showPermissionDeniedDialog() {
        // Names the app by its launcher label, which is what the settings list shows.
        new MaterialAlertDialogBuilder(this)
                .setTitle("Usage access is off")
                .setMessage("Find \"" + getString(R.string.app_name) + "\" in the list and turn it on.")
                .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)))
                .setNegativeButton("Not now", null).show();
    }
}
