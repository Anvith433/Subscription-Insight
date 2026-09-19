package com.example.myjavaapplication;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import java.util.List;

public class RecommendationAdapter extends RecyclerView.Adapter<RecommendationAdapter.ViewHolder> {
    private List<DashBoardResponseDTO> dataList;

    public RecommendationAdapter(List<DashBoardResponseDTO> dataList) {
        this.dataList = dataList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Use the custom item_recommendation layout we discussed
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recommendation, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DashBoardResponseDTO item = dataList.get(position);

        // 1. Set basic text data
        holder.tvAppName.setText(item.getProviderName());
        holder.tvUsage.setText(item.getUsageLabel());
        holder.tvReason.setText(item.getReason());
        holder.tvPrice.setText(item.getPriceLabel());

        // 2. Change Card Border Color based on RecommendationType
        //    DOWNGRADE previously fell through to the default amber, giving it the same
        //    colour as CONSIDER even though it is a specific action, not a "watch it".
        //    switch on a String NPEs on null, so the getter's default is relied on.
        int strokeColor;
        switch (item.getRecommendationType()) {
            case "KEEP":
                strokeColor = Color.parseColor("#4CAF50"); // Green
                break;
            case "CANCEL":
                strokeColor = Color.parseColor("#F44336"); // Red
                break;
            case "DOWNGRADE":
                strokeColor = Color.parseColor("#FF9800"); // Orange - act, but do not drop
                break;
            case "CONSIDER":
            default:
                strokeColor = Color.parseColor("#FFC107"); // Amber/Yellow
                break;
        }

        holder.cardView.setStrokeColor(strokeColor);
    }

    @Override
    public int getItemCount() {
        return dataList == null ? 0 : dataList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvAppName, tvUsage, tvReason, tvPrice;
        MaterialCardView cardView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            tvUsage = itemView.findViewById(R.id.tvUsageMins);
            tvReason = itemView.findViewById(R.id.tvReason);
            tvPrice = itemView.findViewById(R.id.tvPrice);
            cardView = itemView.findViewById(R.id.recommendationCard);
        }
    }
}