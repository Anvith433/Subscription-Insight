package com.example.myjavaapplication;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private TextView tvSummaryPeriod, tvSummarySpend, tvSummaryCount, tvSummarySavings, tvSummaryWaste;
    private TextView tvChartCaption;
    private ApiService apiService;
    private long userId;
    private String jwtToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        barChart = findViewById(R.id.barChart);
        cardSummary = findViewById(R.id.cardSummary);
        tvSummaryPeriod = findViewById(R.id.tvSummaryPeriod);
        tvSummarySpend = findViewById(R.id.tvSummarySpend);
        tvSummaryCount = findViewById(R.id.tvSummaryCount);
        tvSummarySavings = findViewById(R.id.tvSummarySavings);
        tvSummaryWaste = findViewById(R.id.tvSummaryWaste);
        tvChartCaption = findViewById(R.id.tvChartCaption);
        styleChart();

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
                                "No subscriptions on this account yet. Add one first, "
                                        + "then sync.", Toast.LENGTH_LONG).show();
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
        String currency = s.getCurrency();
        int count = s.getActiveSubscriptionCount();

        tvSummaryPeriod.setText(s.getPeriod() == null ? "This month" : Formats.month(s.getPeriod()));
        tvSummarySpend.setText(Formats.money(currency, orZero(s.getTotalMonthlySpend())));
        tvSummaryCount.setText("across " + count + " active " + (count == 1 ? "subscription" : "subscriptions"));

        BigDecimal savings = orZero(s.getPotentialMonthlySavings());
        tvSummarySavings.setText(Formats.money(currency, savings));
        if (savings.signum() == 0) {
            tvSummaryWaste.setText("Everything you pay for is earning its keep.");
        } else {
            // Whole percent: "72.35%" is precision the estimate does not have.
            StringBuilder waste = new StringBuilder(String.format(Locale.ROOT, "%s%% of your spend",
                    orZero(s.getWastedSpendPercent()).setScale(0, RoundingMode.HALF_UP).toPlainString()));
            if (s.getPotentialYearlySavings() != null) {
                waste.append(" · ").append(Formats.money(currency, s.getPotentialYearlySavings()))
                        .append(" over a year");
            }
            if (s.getUnusedCount() > 0) {
                waste.append(" · ").append(s.getUnusedCount()).append(" unused");
            }
            tvSummaryWaste.setText(waste);
        }

        cardSummary.setVisibility(View.VISIBLE);
    }

    /** Every money field on the summary is nullable -- an account with nothing to total. */
    private BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Everything about the chart that does not depend on the data: no grid, no legend, no
     * touch, the app's fonts. Done once, so a reload only has to swap the data.
     */
    private void styleChart() {
        int muted = ContextCompat.getColor(this, R.color.ink_muted);
        Typeface body = ResourcesCompat.getFont(this, R.font.dm_sans_medium);

        barChart.getDescription().setEnabled(false);
        barChart.getLegend().setEnabled(false);
        barChart.setDrawGridBackground(false);
        barChart.setTouchEnabled(false);
        barChart.setFitBars(true);
        barChart.setNoDataText("Sync your usage to see where your time goes.");
        barChart.setNoDataTextColor(muted);
        barChart.setNoDataTextTypeface(body);

        // On a horizontal chart the X axis is the vertical one: the app names.
        XAxis names = barChart.getXAxis();
        names.setPosition(XAxis.XAxisPosition.BOTTOM);
        names.setDrawGridLines(false);
        names.setDrawAxisLine(false);
        names.setGranularity(1f);
        names.setTextColor(muted);
        names.setTextSize(12f);
        names.setTypeface(body);

        YAxis minutes = barChart.getAxisLeft();
        minutes.setAxisMinimum(0f);
        // Headroom past the longest bar, so its label is not clipped at the edge.
        minutes.setSpaceTop(30f);
        minutes.setEnabled(false);
        barChart.getAxisRight().setEnabled(false);
    }

    private void renderBarChart(List<DashBoardResponseDTO> list) {
        // A horizontal chart draws entry 0 at the bottom, so entries are reversed to keep
        // the chart in the same order as the cards underneath it.
        int n = list.size();
        ArrayList<BarEntry> entries = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            DashBoardResponseDTO item = list.get(n - 1 - i);
            entries.add(new BarEntry(i, (float) item.getUsageTime(), item));
            colors.add(ContextCompat.getColor(this, RecommendationAdapter.verdictColor(item.getRecommendationType())));
            names[i] = item.getProviderName();
        }

        BarDataSet set = new BarDataSet(entries, "Usage");
        set.setColors(colors);
        set.setHighlightEnabled(false);
        set.setValueTextSize(12f);
        set.setValueTextColor(ContextCompat.getColor(this, R.color.ink));
        set.setValueTypeface(ResourcesCompat.getFont(this, R.font.dm_sans_bold));
        set.setValueFormatter(new ValueFormatter() {
            @Override
            public String getBarLabel(BarEntry barEntry) {
                DashBoardResponseDTO item = (DashBoardResponseDTO) barEntry.getData();
                return item.hasUsageData() ? Formats.minutes(item.getUsageTime()) : "not synced";
            }
        });

        BarData data = new BarData(set);
        data.setBarWidth(0.6f);
        barChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(names));
        barChart.getXAxis().setLabelCount(n);

        // Every bar gets the same room however many subscriptions there are, rather than
        // two bars filling a tall chart or twelve being crushed into it.
        ViewGroup.LayoutParams params = barChart.getLayoutParams();
        params.height = Math.round((Math.max(n, 2) * 44 + 16) * getResources().getDisplayMetrics().density);
        barChart.setLayoutParams(params);

        barChart.setData(data);
        // The dashboard is monthly, not daily; name the month the data is actually for.
        String period = list.get(0).getBillingPeriod();
        tvChartCaption.setText(period == null
                ? "Time in each app, coloured by its verdict."
                : "Time in each app in " + Formats.month(period) + ", coloured by its verdict.");
        barChart.animateY(700);
        barChart.invalidate();
    }
}
