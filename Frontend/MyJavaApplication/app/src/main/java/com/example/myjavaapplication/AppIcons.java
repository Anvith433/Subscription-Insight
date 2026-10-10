package com.example.myjavaapplication;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.Locale;

/**
 * Shows a subscription's real app icon, or a lettered circle when that app is not on
 * this phone (a subscription added on another device, or since uninstalled).
 *
 * <p>Every layout that uses it stacks an ImageView and a monogram TextView in the same
 * frame; exactly one of the two is visible after {@link #bind}.
 *
 * <p>No cache of its own: PackageManager already caches icon drawables per package, and
 * these lists are a handful of rows long.
 */
final class AppIcons {

    private AppIcons() {
    }

    static void bind(ImageView icon, TextView monogram, String packageName, String label) {
        Drawable drawable = load(icon, packageName);
        if (drawable != null) {
            icon.setImageDrawable(drawable);
            icon.setVisibility(View.VISIBLE);
            monogram.setVisibility(View.GONE);
        } else {
            icon.setImageDrawable(null);
            icon.setVisibility(View.GONE);
            monogram.setText(initial(label));
            monogram.setVisibility(View.VISIBLE);
        }
    }

    private static Drawable load(View view, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return null;
        }
        try {
            return view.getContext().getPackageManager().getApplicationIcon(packageName.trim());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String initial(String label) {
        if (label == null || label.trim().isEmpty()) {
            return "?";
        }
        int first = label.trim().codePointAt(0);
        return new String(Character.toChars(first)).toUpperCase(Locale.getDefault());
    }
}
