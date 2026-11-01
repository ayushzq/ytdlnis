package mod;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** The in-app menu: feature toggles, per-feature counters and a Developer Options shortcut. */
final class ModMenu {
    private static final String DEV_OPTIONS =
            "com.instagram.quickpromotion.debug.devtool.QuickPromotionIGInternalSettingsActivity";

    private ModMenu() {}

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static void show(final Activity a) {
        try {
            Hooks.persistHits(a);
            final Context ctx = new ContextThemeWrapper(a, android.R.style.Theme_DeviceDefault_Dialog_Alert);
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(ctx, 20);
            root.setPadding(pad, dp(ctx, 12), pad, 0);

            TextView hint = new TextView(ctx);
            hint.setText("Counters show how many times a blocked action was intercepted (debug check).");
            hint.setTextSize(12);
            root.addView(hint);

            for (int i = 0; i < Hooks.KEYS.length; i++) {
                final int idx = i;
                final Switch sw = new Switch(ctx);
                int installed = BuildInfo.HOOKS[i];
                String state = installed == 0 ? "NOT in this build" : installed + " hook(s)";
                sw.setText(Hooks.LABELS[i] + "\n" + state + "  |  blocked " + Hooks.hits(i) + "x");
                sw.setChecked(Hooks.isOn(i));
                sw.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));
                sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Hooks.set(a, idx, checked);
                    }
                });
                root.addView(sw);
            }

            Button dev = new Button(ctx);
            dev.setText("Open Developer Options");
            dev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent i = new Intent();
                        i.setClassName(a, DEV_OPTIONS);
                        a.startActivity(i);
                    } catch (Throwable t) {
                        Toast.makeText(a, "Developer Options unavailable: " + t.getClass().getSimpleName(),
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
            root.addView(dev);

            TextView info = new TextView(ctx);
            info.setText("\nPatched in this build:\n" + BuildInfo.SUMMARY);
            info.setTextSize(11);
            root.addView(info);

            ScrollView scroll = new ScrollView(ctx);
            scroll.addView(root);
            new AlertDialog.Builder(ctx)
                    .setTitle("IG Mod  " + BuildInfo.VERSION)
                    .setView(scroll)
                    .setNeutralButton("Reset counters", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            Hooks.resetHits(a);
                        }
                    })
                    .setPositiveButton("Close", null)
                    .show();
        } catch (Throwable t) {
            try {
                Toast.makeText(a, "Mod menu error: " + t, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }
}
