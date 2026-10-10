package com.example.myjavaapplication;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Creates a subscription.
 *
 * <p>The app -- and therefore the package name -- is picked from the device's installed
 * apps rather than typed. That package becomes {@code subscriptions.package_name}, which
 * is the key {@code UserSnapShotService} compares each scanned app against. A typo there
 * does not produce an error; the subscription simply never matches any usage and sits at
 * zero minutes forever. Picking removes the failure mode instead of validating it.
 *
 * <p>No {@code java.time} anywhere in here. This module is minSdk 24 without core library
 * desugaring, so {@code LocalDate} would compile and then throw NoClassDefFoundError on
 * API 24-25. Dates are built with {@link Calendar} and sent as ISO strings, which is what
 * the server's Jackson configuration reads.
 */
public class AddSubscriptionActivity extends AppCompatActivity {

    /** Matches the server's Category enum. Sent as-is; the server matches case-insensitively. */
    private static final String[] CATEGORIES = {
            "OTT", "VIDEO", "MUSIC", "CLOUD", "EDUCATION", "FINANCE",
            "SOCIAL", "SHOPPING", "UTILITIES", "UTILITY", "DEVELOPMENT", "SYSTEM"
    };

    /** Matches the server's Renewal_Cycle enum. */
    private static final String[] CYCLES = {"MONTHLY", "YEARLY", "FREE"};

    private TextInputLayout tilApp, tilPrice;
    private TextInputEditText etApp, etProvider, etCategory, etPrice, etCurrency, etCycle, etStartDate;
    private MaterialButton btnSave;

    private ApiService apiService;
    private String jwtToken;

    private String selectedPackage;
    private String selectedLabel;
    /** What the provider field was last auto-filled with, so a user's own edit is not overwritten. */
    private String autoFilledProvider;

    /** Launchable apps on the device. Null until the background load finishes. */
    private volatile List<AppEntry> installedApps;
    /** Set when the user taps the field before the load completes. Main thread only. */
    private boolean openPickerWhenLoaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_subscription);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            // The collapsing layout draws the title; the toolbar's own would double it.
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tilApp = findViewById(R.id.tilApp);
        tilPrice = findViewById(R.id.tilPrice);
        etApp = findViewById(R.id.etApp);
        etProvider = findViewById(R.id.etProvider);
        etCategory = findViewById(R.id.etCategory);
        etPrice = findViewById(R.id.etPrice);
        etCurrency = findViewById(R.id.etCurrency);
        etCycle = findViewById(R.id.etCycle);
        etStartDate = findViewById(R.id.etStartDate);
        btnSave = findViewById(R.id.btnSave);

        jwtToken = getIntent().getStringExtra("JWT_TOKEN");
        if (jwtToken == null) {
            Toast.makeText(this, "Error: Authentication failed!", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        apiService = RetrofitClient.getClient().create(ApiService.class);

        // Defaults that match the server's own, so the form shows what will be stored
        // rather than leaving the user to guess what a blank field means.
        etCategory.setText(Formats.enumLabel(CATEGORIES[0]));
        etCycle.setText(Formats.enumLabel(CYCLES[0]));
        etCurrency.setText("INR");
        etPrice.setText("0.00");
        etStartDate.setText(today());

        etApp.setOnClickListener(v -> onAppFieldTapped());
        etCategory.setOnClickListener(v -> pick("Category", CATEGORIES, etCategory));
        etCycle.setOnClickListener(v -> pick("Billed", CYCLES, etCycle));
        etStartDate.setOnClickListener(v -> showDatePicker());
        btnSave.setOnClickListener(v -> save());

        // Started here rather than on the first tap: reading every launcher activity's
        // label touches each package's resources, which is slow enough to jank the UI
        // thread on a phone with a lot of apps. By the time the field is tapped it is
        // usually already in memory.
        startLoadingApps();
    }

    // ---------------------------------------------------------------- app picker

    private void startLoadingApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> apps = loadLaunchableApps();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        installedApps = apps;
                        if (openPickerWhenLoaded && !isFinishing()) {
                            openPickerWhenLoaded = false;
                            showAppPicker();
                        }
                    }
                });
            }
        }).start();
    }

    private void onAppFieldTapped() {
        if (installedApps == null) {
            openPickerWhenLoaded = true;
            Toast.makeText(this, "Reading your installed apps...", Toast.LENGTH_SHORT).show();
            return;
        }
        showAppPicker();
    }

    /**
     * Apps with a launcher entry, not every installed package: a subscription belongs to
     * something the user can open, and the full package list is mostly system components.
     * QUERY_ALL_PACKAGES is already declared, so this still sees third-party apps under
     * the Android 11 package-visibility rules.
     */
    private List<AppEntry> loadLaunchableApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN, null);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);

        List<AppEntry> apps = new ArrayList<>();
        try {
            List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
            // Keyed by package: an app with several launcher activities would otherwise
            // appear several times, and only one row can exist per package anyway.
            Map<String, AppEntry> byPackage = new LinkedHashMap<>();
            String self = getPackageName();
            for (ResolveInfo ri : resolved) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(self) || byPackage.containsKey(pkg)) {
                    continue;
                }
                CharSequence label = ri.loadLabel(pm);
                byPackage.put(pkg, new AppEntry(
                        label == null || label.length() == 0 ? pkg : label.toString(), pkg));
            }
            apps.addAll(byPackage.values());
            Collections.sort(apps, new Comparator<AppEntry>() {
                @Override
                public int compare(AppEntry a, AppEntry b) {
                    return String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
                }
            });
        } catch (RuntimeException e) {
            // A failed query must not take the screen down: the user can still be told,
            // and everything except the app field is usable.
            Log.e("AddSubscription", "Could not list installed apps: " + e.getMessage());
        }
        return apps;
    }

    private void showAppPicker() {
        final List<AppEntry> apps = installedApps;
        if (apps == null || apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps found on this device.", Toast.LENGTH_LONG).show();
            return;
        }

        View content = getLayoutInflater().inflate(R.layout.dialog_app_picker, null);
        final ListView list = content.findViewById(R.id.listApps);
        EditText search = content.findViewById(R.id.etAppSearch);

        // A copy, because ArrayAdapter's filter mutates the list it was given. The base
        // getView still binds toString() into tvAppLabel; the override then splits it
        // into name and package and adds the icon, leaving the filter untouched.
        final ArrayAdapter<AppEntry> adapter = new ArrayAdapter<AppEntry>(
                this, R.layout.item_app_picker, R.id.tvAppLabel, new ArrayList<>(apps)) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                AppEntry entry = getItem(position);
                if (entry != null) {
                    ((TextView) row.findViewById(R.id.tvAppLabel)).setText(entry.label);
                    ((TextView) row.findViewById(R.id.tvAppPackage)).setText(entry.packageName);
                    AppIcons.bind((ImageView) row.findViewById(R.id.ivAppIcon),
                            (TextView) row.findViewById(R.id.tvMonogram),
                            entry.packageName, entry.label);
                }
                return row;
            }
        };
        list.setAdapter(adapter);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Choose the app")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .create();

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // ArrayAdapter's built-in filter matches on toString(), which AppEntry
                // defines as "label - package". So this one call searches the visible
                // name and the package id together, and getItem(position) below still
                // resolves against the filtered list.
                adapter.getFilter().filter(s);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AppEntry entry = adapter.getItem(position);
                if (entry != null) {
                    selectApp(entry);
                }
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    private void selectApp(AppEntry entry) {
        selectedPackage = entry.packageName;
        selectedLabel = entry.label;
        // The name in the field, the package under it: the package is what matching
        // keys on, so it stays visible, but it is not what anyone looks for first.
        etApp.setText(entry.label);
        tilApp.setHelperText("Matched on " + entry.packageName);
        tilApp.setError(null);

        // Auto-fill the provider, but never clobber something the user typed. "YouTube"
        // becoming "YouTube Premium" is the normal case, and losing that edit by
        // reopening the picker would be maddening.
        String current = text(etProvider);
        if (current.isEmpty() || current.equals(autoFilledProvider)) {
            etProvider.setText(entry.label);
            autoFilledProvider = entry.label;
        }
    }

    /** One installed app. {@link #toString()} is what the list shows and the filter matches. */
    private static class AppEntry {
        final String label;
        final String packageName;

        AppEntry(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }

        @Override
        public String toString() {
            return label + " — " + packageName;
        }
    }

    // ---------------------------------------------------------------- other pickers

    /** Shows the enum values as words ("Monthly"); save() turns them back with enumValue. */
    private void pick(String title, final String[] values, final TextInputEditText target) {
        final String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = Formats.enumLabel(values[i]);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(labels, (dialog, which) -> target.setText(labels[which]))
                .show();
    }

    private void showDatePicker() {
        Calendar c = Calendar.getInstance();
        // Reopen on the date already shown rather than on today, so correcting a typo
        // does not mean scrolling back from the present.
        String current = text(etStartDate);
        if (current.length() == 10) {
            try {
                c.set(Calendar.YEAR, Integer.parseInt(current.substring(0, 4)));
                c.set(Calendar.MONTH, Integer.parseInt(current.substring(5, 7)) - 1);
                c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(current.substring(8, 10)));
            } catch (NumberFormatException ignored) {
                // Leave the calendar on today.
            }
        }
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(android.widget.DatePicker view, int year, int month, int dayOfMonth) {
                // DatePicker months are zero-based; ISO months are not.
                etStartDate.setText(String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, dayOfMonth));
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private String today() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    // ---------------------------------------------------------------- save

    /**
     * Only two things are checked here: that an app was picked, and that the price parses.
     * Everything else the server either defaults or rejects with a specific message, and
     * duplicating its rules would create a second set to keep in step with the first.
     */
    private void save() {
        if (selectedPackage == null) {
            tilApp.setError("Pick the app this subscription is for");
            return;
        }
        tilApp.setError(null);

        BigDecimal price;
        String priceText = text(etPrice);
        if (priceText.isEmpty()) {
            price = BigDecimal.ZERO;
        } else {
            try {
                price = new BigDecimal(priceText);
            } catch (NumberFormatException e) {
                tilPrice.setError("Enter an amount like 199.00");
                return;
            }
        }
        tilPrice.setError(null);

        String provider = text(etProvider);
        if (provider.isEmpty()) {
            // The server requires a provider name; the app label is a better fallback
            // than an error the user has to read to understand.
            provider = selectedLabel;
        }
        String currency = text(etCurrency).toUpperCase(Locale.ROOT);

        SubscriptionRequestBody body = SubscriptionRequestBody.create(
                provider,
                selectedPackage,
                Formats.enumValue(text(etCategory)),
                Formats.enumValue(text(etCycle)),
                text(etStartDate),
                price,
                currency.isEmpty() ? null : currency);

        // A second tap would race the first and lose to its own 409.
        btnSave.setEnabled(false);
        apiService.createSubscription("Bearer " + jwtToken, body)
                .enqueue(new Callback<SubscriptionResponse>() {
                    @Override
                    public void onResponse(Call<SubscriptionResponse> call,
                                           Response<SubscriptionResponse> response) {
                        if (response.isSuccessful()) {
                            Toast.makeText(AddSubscriptionActivity.this,
                                    "Subscription added. Sync to match your usage to it.",
                                    Toast.LENGTH_LONG).show();
                            finish();
                            return;
                        }
                        btnSave.setEnabled(true);
                        if (response.code() == 409) {
                            // The server enforces one subscription per package per user;
                            // two would double-count the same usage.
                            tilApp.setError("You already have a subscription for that app");
                            Toast.makeText(AddSubscriptionActivity.this,
                                    "You already have a subscription for that app.",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(AddSubscriptionActivity.this,
                                    "Could not save (" + response.code() + ")",
                                    Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<SubscriptionResponse> call, Throwable t) {
                        btnSave.setEnabled(true);
                        Log.e("AddSubscription", "Create failed: " + t.getMessage());
                        Toast.makeText(AddSubscriptionActivity.this,
                                "Failed to connect to backend", Toast.LENGTH_LONG).show();
                    }
                });
    }

    private String text(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }
}
