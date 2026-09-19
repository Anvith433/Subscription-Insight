package com.example.myjavaapplication;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.google.android.material.card.MaterialCardView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DashboardActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private BarChart barChart;
    private MaterialCardView cardSummary;
    private TextView tvSummaryPeriod, tvSummarySpend, tvSummarySavings, tvSummaryWaste;
    private ApiService apiService;
    private long userId;
    private String jwtToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        barChart = findViewById(R.id.barChart);
        cardSummary = findViewById(R.id.cardSummary);
        tvSummaryPeriod = findViewById(R.id.tvSummaryPeriod);
        tvSummarySpend = findViewById(R.id.tvSummarySpend);
        tvSummarySavings = findViewById(R.id.tvSummarySavings);
        tvSummaryWaste = findViewById(R.id.tvSummaryWaste);

        userId = getIntent().getLongExtra("USER_ID", -1);
        jwtToken = getIntent().getStringExtra("JWT_TOKEN"); // Get Token

        if (userId == -1 || jwtToken == null) {
            Toast.makeText(this, "Error: Authentication failed!", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        apiService = RetrofitClient.getClient().create(ApiService.class);

        loadData();
        // Issued alongside loadData, not after it: the two endpoints are independent, so
        // waiting for the list before asking for the totals would only add a round trip.
        loadSummary();
    }

    private void loadData() {
        // Send JWT Token in Header
        apiService.getDashboard("Bearer " + jwtToken, userId).enqueue(new Callback<List<DashBoardResponseDTO>>() {
            @Override
            public void onResponse(Call<List<DashBoardResponseDTO>> call, Response<List<DashBoardResponseDTO>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<DashBoardResponseDTO> list = response.body();

                    if (list.isEmpty()) {
                        // The server resolves the period itself and returns every
                        // subscription, so an empty list means this account has no
                        // subscriptions -- not "nothing was used today".
                        Toast.makeText(DashboardActivity.this,
                                "No subscriptions on this account yet -- add one from "
                                        + "Manage Subscriptions.", Toast.LENGTH_LONG).show();
                    } else {
                        renderBarChart(list);
                        RecommendationAdapter adapter = new RecommendationAdapter(list);
                        recyclerView.setAdapter(adapter);
                    }
                } else {
                    Toast.makeText(DashboardActivity.this, "Server Error: " + response.code(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<List<DashBoardResponseDTO>> call, Throwable t) {
                Log.e("Dashboard", "Network Failure: " + t.getMessage());
                Toast.makeText(DashboardActivity.this, "Failed to connect to backend", Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * The account-level figures: total spend, and what cancelling the CANCEL verdicts
     * would recover. The card stays hidden on any failure -- these numbers are the
     * headline claim of the whole product, so showing stale or blank ones would be worse
     * than showing none.
     */
    private void loadSummary() {
        apiService.getDashboardSummary("Bearer " + jwtToken, userId)
                .enqueue(new Callback<DashboardSummaryResponse>() {
                    @Override
                    public void onResponse(Call<DashboardSummaryResponse> call,
                                           Response<DashboardSummaryResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            bindSummary(response.body());
                        } else {
                            Log.w("Dashboard", "Summary unavailable: " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(Call<DashboardSummaryResponse> call, Throwable t) {
                        // Silent: the recommendation list below carries the same story per
                        // row, and loadData already reports a connection failure once.
                        Log.w("Dashboard", "Summary failed: " + t.getMessage());
                    }
                });
    }

    private void bindSummary(DashboardSummaryResponse s) {
        // Currency comes from the response rather than a hardcoded symbol: the server
        // stores one per subscription and the seed data is not all INR.
        String currency = s.getCurrency() == null ? "" : s.getCurrency() + " ";

        tvSummaryPeriod.setText(s.getPeriod() == null ? "This month" : s.getPeriod());
        tvSummarySpend.setText(String.format(Locale.ROOT, "%s%s across %d active %s",
                currency,
                plain(s.getTotalMonthlySpend()),
                s.getActiveSubscriptionCount(),
                s.getActiveSubscriptionCount() == 1 ? "subscription" : "subscriptions"));
        tvSummarySavings.setText(currency + plain(s.getPotentialMonthlySavings()) + " / month");
        tvSummaryWaste.setText(String.format(Locale.ROOT, "%s%% of your spend · %d with no usage",
                plain(s.getWastedSpendPercent()), s.getUnusedCount()));

        cardSummary.setVisibility(View.VISIBLE);
    }

    /** Every money field on the summary is nullable -- an account with nothing to total. */
    private String plain(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    private void renderBarChart(List<DashBoardResponseDTO> list) {
        ArrayList<BarEntry> entries = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            entries.add(new BarEntry(i, (float) list.get(i).getUsageTime()));
        }

        BarDataSet set = new BarDataSet(entries, "App Usage (Mins)");
        set.setColors(ColorTemplate.MATERIAL_COLORS);
        set.setValueTextSize(12f);

        BarData data = new BarData(set);
        barChart.setData(data);
        // The dashboard is monthly, not daily; name the month the data is actually for.
        String period = list.get(0).getBillingPeriod();
        barChart.getDescription().setText(
                period == null ? "Usage per subscription (minutes)"
                               : "Usage per subscription, " + period + " (minutes)");
        barChart.animateY(1000);
        barChart.invalidate();
    }
}
