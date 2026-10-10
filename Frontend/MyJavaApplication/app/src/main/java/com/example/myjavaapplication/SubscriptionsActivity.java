package com.example.myjavaapplication;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import java.math.BigDecimal;
import java.util.List;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Lists the user's subscriptions and is the way to add, cancel and reactivate them.
 *
 * <p>This screen is what makes the product usable from the phone alone. Until it existed
 * a subscription could only be created with curl or an INSERT, so a new user hit "Add a
 * subscription first, then sync." with nowhere to go -- and the usage matcher, the
 * cost-per-hour figures and the whole dashboard were unreachable behind it.
 */
public class SubscriptionsActivity extends AppCompatActivity implements SubscriptionAdapter.Listener {

    private RecyclerView recyclerView;
    private View emptyState;
    private TextView tvHeaderSubtitle;
    private ApiService apiService;
    private long userId;
    private String jwtToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_subscriptions);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            // The collapsing layout draws the title; the toolbar's own would double it.
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        recyclerView = findViewById(R.id.recyclerSubscriptions);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        emptyState = findViewById(R.id.emptyState);
        tvHeaderSubtitle = findViewById(R.id.tvHeaderSubtitle);

        userId = getIntent().getLongExtra("USER_ID", -1);
        jwtToken = getIntent().getStringExtra("JWT_TOKEN");

        if (userId == -1 || jwtToken == null) {
            Toast.makeText(this, "Error: Authentication failed!", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        apiService = RetrofitClient.getClient().create(ApiService.class);

        ExtendedFloatingActionButton fab = findViewById(R.id.fabAdd);
        fab.setOnClickListener(v -> {
            Intent intent = new Intent(SubscriptionsActivity.this, AddSubscriptionActivity.class);
            intent.putExtra("USER_ID", userId);
            intent.putExtra("JWT_TOKEN", jwtToken);
            startActivity(intent);
        });
    }

    /**
     * Reloaded on every resume rather than only on create. Coming back from the add
     * screen or from a cancel is exactly when the list is stale, and re-fetching is
     * cheaper to get right than passing results back and patching the adapter.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (apiService != null) {
            loadSubscriptions();
        }
    }

    private void loadSubscriptions() {
        // activeOnly is deliberately not sent: cancelled subscriptions stay in the list
        // so a mis-tap can be undone, which is the only route back from a cancel.
        apiService.getSubscriptions("Bearer " + jwtToken).enqueue(new Callback<List<SubscriptionResponse>>() {
            @Override
            public void onResponse(Call<List<SubscriptionResponse>> call,
                                   Response<List<SubscriptionResponse>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<SubscriptionResponse> list = response.body();
                    recyclerView.setAdapter(new SubscriptionAdapter(list, SubscriptionsActivity.this));
                    showEmptyState(list.isEmpty());
                    bindHeader(list);
                } else {
                    Toast.makeText(SubscriptionsActivity.this,
                            "Could not load subscriptions (" + response.code() + ")",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<List<SubscriptionResponse>> call, Throwable t) {
                Log.e("Subscriptions", "Network Failure: " + t.getMessage());
                Toast.makeText(SubscriptionsActivity.this,
                        "Failed to connect to backend", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showEmptyState(boolean empty) {
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /**
     * "3 active · ₹1,046 a month" under the title. Counts the same rows the server's
     * monthly spend does: active ones, at their monthly-normalised price. The total is
     * left off when the active rows are in more than one currency, since adding rupees
     * to dollars would produce a confident, wrong number.
     */
    private void bindHeader(List<SubscriptionResponse> list) {
        int active = 0;
        BigDecimal total = BigDecimal.ZERO;
        String currency = null;
        boolean mixedCurrencies = false;
        for (SubscriptionResponse sub : list) {
            if (sub.getStatus() != null && !"ACTIVE".equals(sub.getStatus())) {
                continue;
            }
            active++;
            BigDecimal monthly = sub.getMonthlyPrice() == null ? sub.getPrice() : sub.getMonthlyPrice();
            if (monthly != null) {
                total = total.add(monthly);
            }
            if (currency == null) {
                currency = sub.getCurrency();
            } else if (sub.getCurrency() != null && !currency.equalsIgnoreCase(sub.getCurrency())) {
                mixedCurrencies = true;
            }
        }

        if (list.isEmpty()) {
            tvHeaderSubtitle.setText("Everything you pay for, in one place.");
        } else if (active == 0) {
            tvHeaderSubtitle.setText("Nothing active right now.");
        } else if (mixedCurrencies) {
            tvHeaderSubtitle.setText(active + " active");
        } else {
            tvHeaderSubtitle.setText(active + " active · " + Formats.money(currency, total) + " a month");
        }
    }

    @Override
    public void onCancelRequested(final SubscriptionResponse subscription) {
        String name = subscription.getProviderName() == null
                ? "this subscription" : subscription.getProviderName();
        // Says what is kept as well as what changes. The server's cancel is a soft one --
        // the snapshots and billing rows survive because they are the evidence behind the
        // verdict -- and a dialog that implied deletion would misrepresent it.
        new MaterialAlertDialogBuilder(this)
                .setTitle("Cancel " + name + "?")
                .setMessage("It stops counting toward your monthly spend.\n\n"
                        + "Your usage history is kept, and you can reactivate it from this "
                        + "screen at any time.")
                .setNegativeButton("Keep it", null)
                .setPositiveButton("Cancel subscription", (dialog, which) -> doCancel(subscription))
                .show();
    }

    private void doCancel(SubscriptionResponse subscription) {
        apiService.cancelSubscription("Bearer " + jwtToken, subscription.getId())
                .enqueue(new Callback<SubscriptionResponse>() {
                    @Override
                    public void onResponse(Call<SubscriptionResponse> call,
                                           Response<SubscriptionResponse> response) {
                        if (response.isSuccessful()) {
                            Toast.makeText(SubscriptionsActivity.this,
                                    "Cancelled. Usage history kept.", Toast.LENGTH_SHORT).show();
                            loadSubscriptions();
                        } else {
                            Toast.makeText(SubscriptionsActivity.this,
                                    "Could not cancel (" + response.code() + ")",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<SubscriptionResponse> call, Throwable t) {
                        Log.e("Subscriptions", "Cancel failed: " + t.getMessage());
                        Toast.makeText(SubscriptionsActivity.this,
                                "Failed to connect to backend", Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Override
    public void onReactivateRequested(SubscriptionResponse subscription) {
        // A status-only body: the server's update skips null fields, so the stored price,
        // category and dates are left exactly as they were.
        apiService.updateSubscription(
                        "Bearer " + jwtToken,
                        subscription.getId(),
                        SubscriptionRequestBody.statusOnly("ACTIVE"))
                .enqueue(new Callback<SubscriptionResponse>() {
                    @Override
                    public void onResponse(Call<SubscriptionResponse> call,
                                           Response<SubscriptionResponse> response) {
                        if (response.isSuccessful()) {
                            Toast.makeText(SubscriptionsActivity.this,
                                    "Reactivated.", Toast.LENGTH_SHORT).show();
                            loadSubscriptions();
                        } else {
                            Toast.makeText(SubscriptionsActivity.this,
                                    "Could not reactivate (" + response.code() + ")",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<SubscriptionResponse> call, Throwable t) {
                        Log.e("Subscriptions", "Reactivate failed: " + t.getMessage());
                        Toast.makeText(SubscriptionsActivity.this,
                                "Failed to connect to backend", Toast.LENGTH_LONG).show();
                    }
                });
    }
}
