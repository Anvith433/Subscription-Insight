package com.example.myjavaapplication;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import java.math.BigDecimal;
import java.util.List;

/**
 * The user's subscriptions, one card each.
 *
 * <p>Styled to match {@link RecommendationAdapter}: the app's own icon, then what it is
 * and what it costs. An active row is the normal case and carries no badge; a paused or
 * cancelled one gets a pill naming its state and is dimmed, so the list can still be
 * read at a glance without opening anything.
 *
 * <p>Like RecommendationAdapter this stays dumb -- the overflow menu reports the chosen
 * action through {@link Listener} and the activity decides what to do, so the network
 * calls and the confirmation dialog live with the token rather than in a view holder.
 */
public class SubscriptionAdapter extends RecyclerView.Adapter<SubscriptionAdapter.ViewHolder> {

    /** What the activity implements to act on a card's overflow menu. */
    public interface Listener {
        void onCancelRequested(SubscriptionResponse subscription);

        void onReactivateRequested(SubscriptionResponse subscription);
    }

    private final List<SubscriptionResponse> items;
    private final Listener listener;

    public SubscriptionAdapter(List<SubscriptionResponse> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_subscription, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final SubscriptionResponse item = items.get(position);

        String name = item.getProviderName() == null ? "(unnamed)" : item.getProviderName();
        holder.tvProviderName.setText(name);
        AppIcons.bind(holder.ivAppIcon, holder.tvMonogram, item.getPackageName(), name);
        // The package is the key the whole usage matcher turns on, so it is shown rather
        // than hidden: if a subscription never picks up usage, this is the line to check.
        holder.tvPackageName.setText(
                item.getPackageName() == null ? "no package linked" : item.getPackageName());
        bindPrice(holder, item);
        holder.tvMeta.setText(metaLabel(item));

        final String status = item.getStatus() == null ? "ACTIVE" : item.getStatus();
        final boolean active = "ACTIVE".equals(status);

        // A cancelled subscription stays in the list so it can be reactivated, but it
        // should not compete for attention with the ones actually costing money.
        if (active) {
            holder.tvStatusBadge.setVisibility(View.GONE);
            holder.cardView.setAlpha(1f);
        } else {
            holder.tvStatusBadge.setText(Formats.enumLabel(status));
            holder.tvStatusBadge.setVisibility(View.VISIBLE);
            holder.cardView.setAlpha(0.6f);
        }

        holder.btnOverflow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PopupMenu menu = new PopupMenu(v.getContext(), v);
                // Built in code rather than a menu resource: which single item appears
                // depends on the row's status, so a static resource would have to be
                // filtered at show time anyway.
                if (active) {
                    menu.getMenu().add("Cancel subscription");
                } else {
                    menu.getMenu().add("Reactivate");
                }
                menu.setOnMenuItemClickListener(menuItem -> {
                    if (active) {
                        listener.onCancelRequested(item);
                    } else {
                        listener.onReactivateRequested(item);
                    }
                    return true;
                });
                menu.show();
            }
        });
    }

    /**
     * The monthly-normalised price, so a yearly plan sits on the same scale as a monthly
     * one. Falls back to the raw price when the server has not normalised it, and to a
     * dash when there is no price at all. A zero price says "Free" -- a free tier is
     * legitimate here, and "₹0 a month" reads like missing data.
     */
    private void bindPrice(ViewHolder holder, SubscriptionResponse item) {
        BigDecimal monthly = item.getMonthlyPrice() == null ? item.getPrice() : item.getMonthlyPrice();
        if (monthly == null) {
            holder.tvPrice.setText("—");
            holder.tvPriceUnit.setVisibility(View.GONE);
        } else if (monthly.signum() == 0) {
            holder.tvPrice.setText("Free");
            holder.tvPriceUnit.setVisibility(View.GONE);
        } else {
            holder.tvPrice.setText(Formats.money(item.getCurrency(), monthly));
            holder.tvPriceUnit.setVisibility(View.VISIBLE);
        }
    }

    private String metaLabel(SubscriptionResponse item) {
        StringBuilder sb = new StringBuilder();
        if (item.getCategory() != null) {
            sb.append(Formats.enumLabel(item.getCategory()));
        }
        // "Monthly" is left out: the price already says "a month", and the line is short
        // on room. Any other cycle is kept, since it changes what that price means.
        if (item.getRenewalCycle() != null && !"MONTHLY".equalsIgnoreCase(item.getRenewalCycle())) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(Formats.enumLabel(item.getRenewalCycle()));
        }
        // Renewal date is the more useful of the two dates -- what is about to be charged
        // beats when it started -- so it wins when both are present.
        if (item.getRenewalDate() != null) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("renews ").append(Formats.shortDate(item.getRenewalDate()));
        } else if (item.getStartDate() != null) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("since ").append(Formats.shortDate(item.getStartDate()));
        }
        return sb.length() == 0 ? "No details" : sb.toString();
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvProviderName, tvPackageName, tvPrice, tvPriceUnit, tvMeta, tvStatusBadge, tvMonogram;
        ImageView ivAppIcon;
        ImageButton btnOverflow;
        MaterialCardView cardView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvProviderName = itemView.findViewById(R.id.tvProviderName);
            tvPackageName = itemView.findViewById(R.id.tvPackageName);
            tvPrice = itemView.findViewById(R.id.tvPrice);
            tvPriceUnit = itemView.findViewById(R.id.tvPriceUnit);
            tvMonogram = itemView.findViewById(R.id.tvMonogram);
            ivAppIcon = itemView.findViewById(R.id.ivAppIcon);
            tvMeta = itemView.findViewById(R.id.tvMeta);
            tvStatusBadge = itemView.findViewById(R.id.tvStatusBadge);
            btnOverflow = itemView.findViewById(R.id.btnOverflow);
            cardView = itemView.findViewById(R.id.subscriptionCard);
        }
    }
}
