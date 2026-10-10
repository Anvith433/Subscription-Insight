package com.example.myjavaapplication;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class RecommendationAdapter extends RecyclerView.Adapter<RecommendationAdapter.ViewHolder> {
    private List<DashBoardResponseDTO> dataList;

    public RecommendationAdapter(List<DashBoardResponseDTO> dataList) {
        this.dataList = dataList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recommendation, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DashBoardResponseDTO item = dataList.get(position);
        Context context = holder.itemView.getContext();
        String type = item.getRecommendationType();
        String currency = item.getCurrency();

        holder.tvAppName.setText(item.getProviderName());
        AppIcons.bind(holder.ivAppIcon, holder.tvMonogram, item.getPackageName(), item.getProviderName());

        holder.tvVerdict.setText(verdictLabel(type));
        holder.tvVerdict.setTextColor(ContextCompat.getColor(context, verdictColor(type)));
        ViewCompat.setBackgroundTintList(holder.tvVerdict,
                ColorStateList.valueOf(ContextCompat.getColor(context, verdictBackground(type))));

        // Monthly-normalised, so a yearly plan reads on the same scale as the rest.
        holder.tvPrice.setText(Formats.money(currency,
                item.getMonthlyCost() != null ? item.getMonthlyCost() : item.getPrice()));

        // No snapshot is "unknown", not zero minutes, and the two warrant opposite advice,
        // so the strip says which one it is rather than printing a bare 0.
        if (item.hasUsageData()) {
            holder.tvUsage.setText(Formats.minutes(item.getUsageMinutes()));
            holder.tvUsageLabel.setText("used");
        } else {
            holder.tvUsage.setText("—");
            holder.tvUsageLabel.setText("not synced");
        }
        holder.tvCostPerHour.setText(Formats.rate(currency, item.getCostPerHour()));

        holder.tvReason.setText(item.getReason());

        Double savings = item.getEstimatedMonthlySavings();
        if (savings != null && savings > 0) {
            String amount = Formats.money(currency, savings);
            holder.tvSavings.setText("CANCEL".equals(type)
                    ? "Cancelling frees up " + amount + " a month."
                    : "Acting on this saves about " + amount + " a month.");
            holder.tvSavings.setTextColor(ContextCompat.getColor(context, verdictColor(type)));
            holder.tvSavings.setVisibility(View.VISIBLE);
        } else {
            holder.tvSavings.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return dataList == null ? 0 : dataList.size();
    }

    /**
     * What the pill says. CONSIDER reads as "Rethink": on its own in a pill, "Consider"
     * leaves you asking "consider what?".
     */
    static String verdictLabel(String type) {
        switch (type) {
            case "KEEP":
                return "Keep";
            case "CANCEL":
                return "Cancel";
            case "DOWNGRADE":
                return "Downgrade";
            case "CONSIDER":
            default:
                return "Rethink";
        }
    }

    /**
     * One colour per verdict, shared with the dashboard chart so a bar and its card match.
     * DOWNGRADE gets its own: it is a specific action, not a "watch it" like CONSIDER.
     * switch on a String NPEs on null, so the getter's non-null default is relied on.
     */
    @ColorRes
    static int verdictColor(String type) {
        switch (type) {
            case "KEEP":
                return R.color.verdict_keep;
            case "CANCEL":
                return R.color.verdict_cancel;
            case "DOWNGRADE":
                return R.color.verdict_downgrade;
            case "CONSIDER":
            default:
                return R.color.verdict_consider;
        }
    }

    @ColorRes
    static int verdictBackground(String type) {
        switch (type) {
            case "KEEP":
                return R.color.verdict_keep_bg;
            case "CANCEL":
                return R.color.verdict_cancel_bg;
            case "DOWNGRADE":
                return R.color.verdict_downgrade_bg;
            case "CONSIDER":
            default:
                return R.color.verdict_consider_bg;
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvAppName, tvVerdict, tvMonogram, tvPrice, tvUsage, tvUsageLabel, tvCostPerHour,
                tvReason, tvSavings;
        ImageView ivAppIcon;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            tvVerdict = itemView.findViewById(R.id.tvVerdict);
            tvMonogram = itemView.findViewById(R.id.tvMonogram);
            ivAppIcon = itemView.findViewById(R.id.ivAppIcon);
            tvPrice = itemView.findViewById(R.id.tvPrice);
            tvUsage = itemView.findViewById(R.id.tvUsageMins);
            tvUsageLabel = itemView.findViewById(R.id.tvUsageLabel);
            tvCostPerHour = itemView.findViewById(R.id.tvCostPerHour);
            tvReason = itemView.findViewById(R.id.tvReason);
            tvSavings = itemView.findViewById(R.id.tvSavings);
        }
    }
}
