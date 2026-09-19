package com.example.myjavaapplication;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import bot.box.appusage.contract.UsageContracts;
import bot.box.appusage.handler.Monitor;
import bot.box.appusage.model.AppData;
import bot.box.appusage.utils.Duration;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity implements UsageContracts.View {

    private TextInputEditText etEmail, etPassword, etActualUsername, etConfirmPassword;
    private TextInputLayout tilActualUsername, tilConfirmPassword, tilEmail;
    private MaterialButton btnLogin, btnPermission, btnSubscriptions, btnSync, btnDashboard;
    private View layoutPostLogin;
    private TextView tvSignupLink, tvTitle;
    private boolean isLoggedIn = false;
    private boolean isSignupMode = false;
    private ApiService apiService;
    // Set from the login response. Deliberately null until then: the previous default
    // of 1L meant every install silently operated on user 1's subscriptions, and a
    // wrong-but-plausible id is worse than no id, because nothing ever fails visibly.
    private Long loggedInUserId = null;
    private String jwtToken = "";
    private String selectedPeriod = "WEEK";

    // What to report to the server, refreshed from GET /api/subscriptions immediately
    // before each scan. This replaced a hardcoded list of nine provider names, which
    // silently dropped every other app on the device -- so a subscription for anything
    // outside those nine could never sync, however correctly the server matched it.
    // Deriving the filter from the user's own subscriptions is also the more private
    // arrangement: only packages the user has told us about ever leave the device.
    // Lowercased on insert, because both server-side lookups are case-insensitive.
    private final Set<String> trackedPackages = new HashSet<>();
    private final Set<String> trackedProviderNames = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        apiService = RetrofitClient.getClient().create(ApiService.class);

        tvTitle = findViewById(R.id.tvTitle);
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

        tilActualUsername.setVisibility(View.GONE);
        tilConfirmPassword.setVisibility(View.GONE);
        layoutPostLogin.setVisibility(View.GONE);

        tvSignupLink.setOnClickListener(v -> {
            isSignupMode = true;
            tilActualUsername.setVisibility(View.VISIBLE);
            tilConfirmPassword.setVisibility(View.VISIBLE);
            tilEmail.setHint("Email Address");
            btnLogin.setText("Create Account");
            tvTitle.setText("Sign Up");
            tvSignupLink.setVisibility(View.GONE);
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
                showSyncOptions();
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

    private void showSyncOptions() {
        // The month comes first and is the recommendation. It is the only window that
        // covers a whole billing period, so it is the only one the server can turn into
        // a firm verdict; the other two are labelled so the choice is informed rather
        // than a guess between three equal-looking options. The previous order put
        // "Today's Usage" first, which made the least useful scan the easiest to pick.
        String[] options = {"This Month (recommended)", "This Week (provisional)", "Today (provisional)"};
        new AlertDialog.Builder(this)
                .setTitle("Select Sync Period")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            selectedPeriod = "MONTH";
                            loadSubscriptionsThenScan(Duration.MONTH);
                            break;
                        case 1:
                            selectedPeriod = "WEEK";
                            loadSubscriptionsThenScan(Duration.WEEK);
                            break;
                        case 2:
                            selectedPeriod = "TODAY";
                            loadSubscriptionsThenScan(Duration.TODAY);
                            break;
                    }
                }).show();
    }

    /**
     * Fetches the user's subscriptions, then starts the device scan.
     *
     * <p>The order matters and is not incidental: {@link #getUsageData} runs as the
     * scan's callback and needs the subscription sets to decide what to report, so the
     * fetch has to have completed by then. Kicking off both at once would race.
     *
     * <p>If the fetch fails, the sync is abandoned. It deliberately does not fall back
     * to reporting every installed app and letting the server sort them out: that would
     * send the user's entire app-usage profile over the wire -- in cleartext, on this
     * build -- to work around a matching problem.
     */
    private void loadSubscriptionsThenScan(int duration) {
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
                            "No subscriptions yet -- add one under \"2. Manage Subscriptions\", "
                                    + "then sync.", Toast.LENGTH_LONG).show();
                    return;
                }

                trackedPackages.clear();
                trackedProviderNames.clear();
                for (SubscriptionResponse sub : subs) {
                    String pkg = sub.getPackageName();
                    if (pkg != null && !pkg.trim().isEmpty()) {
                        trackedPackages.add(pkg.trim().toLowerCase(Locale.ROOT));
                    }
                    String provider = sub.getProviderName();
                    if (provider != null && !provider.trim().isEmpty()) {
                        trackedProviderNames.add(provider.trim().toLowerCase(Locale.ROOT));
                    }
                }
                Log.i("USAGE_SYNC", "Filtering against " + trackedPackages.size() + " package(s) and "
                        + trackedProviderNames.size() + " provider name(s)");

                Monitor.scan().getAppLists(MainActivity.this).fetchFor(duration);
            }

            @Override
            public void onFailure(Call<List<SubscriptionResponse>> call, Throwable t) {
                Toast.makeText(MainActivity.this,
                        "Couldn't load your subscriptions; sync cancelled.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void getUsageData(List<AppData> usageData, long mTotalUsage, int duration) {
        if (usageData != null && !usageData.isEmpty()) {
            if (!isLoggedIn || loggedInUserId == null) {
                Toast.makeText(this, "Please Login First!", Toast.LENGTH_SHORT).show();
                return;
            }

            List<AppUsageInfo> cleanList = new ArrayList<>();
            PackageManager pm = getPackageManager();

            for (AppData data : usageData) {
                try {
                    String appLabel = pm.getApplicationLabel(pm.getApplicationInfo(data.mPackageName, 0)).toString();

                    // Mirrors the server's own two-tier match: package name first,
                    // because it is a stable OS identifier, then the display label as a
                    // fallback for subscriptions whose package_name is unset.
                    //
                    // The label is no longer rewritten to a canonical name. The old code
                    // did `appLabel = tracked` on a substring hit, and because "YouTube"
                    // preceded "YouTube Premium" in the list, com.google.android.youtube
                    // was always relabelled plain "YouTube" -- which is not the provider
                    // name the fallback lookup needs. Sending the device's real label
                    // means the two tiers stay independent.
                    boolean isTracked =
                            (data.mPackageName != null
                                    && trackedPackages.contains(
                                            data.mPackageName.trim().toLowerCase(Locale.ROOT)))
                            || trackedProviderNames.contains(appLabel.trim().toLowerCase(Locale.ROOT));

                    if (isTracked) {
                        long mins = data.mUsageTime / 60000;
                        // Never round a used app down to zero: the server reads zero
                        // minutes as a cancel signal, so truncating 40 seconds would
                        // fabricate one.
                        long finalMins = (data.mUsageTime > 0 && mins == 0) ? 1 : mins;

                        // The package name is sent alongside the label so the server can
                        // match on the stable OS identifier. The label is still sent as a
                        // fallback for subscriptions whose package_name column is unset.
                        cleanList.add(new AppUsageInfo(data.mPackageName, appLabel, finalMins));
                        Log.i("USAGE_SYNC", "Adding Tracked App: " + appLabel
                                + " (" + data.mPackageName + ") | " + finalMins + "m");
                    }
                } catch (Exception e) {
                    // Skip if error
                }
            }

            if (cleanList.isEmpty()) {
                Toast.makeText(this, "None of your subscriptions were used in this period.", Toast.LENGTH_LONG).show();
                return;
            }

            UsageRequest request = new UsageRequest(loggedInUserId, cleanList, selectedPeriod);
            apiService.syncUsage("Bearer " + jwtToken, request).enqueue(new Callback<UsageSyncResponse>() {
                @Override
                public void onResponse(Call<UsageSyncResponse> call, Response<UsageSyncResponse> response) {
                    if (!response.isSuccessful()) {
                        Toast.makeText(MainActivity.this, "Sync Failed: " + response.code(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    UsageSyncResponse body = response.body();
                    if (body == null) {
                        Toast.makeText(MainActivity.this, "Sync Successful!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // A 200 with nothing matched is the usual real failure -- an app whose
                    // name matches no subscription -- so it is reported as such rather
                    // than as unqualified success.
                    if (body.getSyncedCount() == 0) {
                        Toast.makeText(MainActivity.this,
                                "Nothing synced: none of these apps match a subscription.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(MainActivity.this,
                                "Synced " + body.getSyncedCount() + " subscription(s) for "
                                        + body.getPeriod()
                                        + (body.getSkippedCount() > 0
                                                ? " (" + body.getSkippedCount() + " app(s) not tracked)"
                                                : ""),
                                Toast.LENGTH_LONG).show();
                    }
                    Log.i("USAGE_SYNC", "synced=" + body.getSynced() + " skipped=" + body.getSkipped());
                }
                @Override
                public void onFailure(Call<UsageSyncResponse> call, Throwable t) {
                    Toast.makeText(MainActivity.this, "Laptop unreachable", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override public void showProgress() {}
    @Override public void hideProgress() {}

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
        Toast.makeText(this, "Welcome " + user, Toast.LENGTH_SHORT).show();
    }

    private void switchToLoginMode() {
        isSignupMode = false;
        tilActualUsername.setVisibility(View.GONE);
        tilConfirmPassword.setVisibility(View.GONE);
        tilEmail.setHint("Username / Email");
        btnLogin.setText("Login");
        tvTitle.setText("Subscription Insight");
        tvSignupLink.setVisibility(View.VISIBLE);
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
        new AlertDialog.Builder(this).setTitle("Permission Needed").setMessage("Find 'MyJavaApplication' in the list and turn it ON.")
                .setPositiveButton("Settings", (d, w) -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)))
                .setNegativeButton("Cancel", null).show();
    }
}
