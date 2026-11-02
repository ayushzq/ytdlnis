package mod;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Runtime half of the Instagram mod. The smali patches call the static hooks below;
 * every hook is wrapped so a failure here can never crash the host app.
 */
public final class Hooks {
    static final String PREFS = "igmod_prefs";
    static final String[] KEYS = {"story", "dm", "typing", "ads", "uiads", "dl"};
    static final String[] LABELS = {
        "Hide story seen", "Hide DM seen", "Hide typing indicator", "Block ads (network)",
        "Hide ad posts (on screen)", "Download buttons"};
    private static final String TAG = "igmod_button";

    private static final boolean[] ON = {true, true, true, true, true, true};
    private static final int[] HITS = new int[6];
    private static volatile boolean loaded;
    /** why the most recent ad was hidden (shown in the menu so false positives are easy to spot) */
    static volatile String lastAdReason = "-";

    private Hooks() {}

    // ---- string hooks: wrap a const-string; blocked requests are redirected to a dead path ----
    public static String story(String s) { return flag(0) ? "ghost_blocked/" + s : s; }
    public static String dm(String s) { return flag(1) ? "ghost_blocked/" + s : s; }
    public static String typing(String s) { return flag(2) ? "ghost_blocked/" + s : s; }
    public static String ads(String s) { return flag(3) ? "ghost_blocked/" + s : s; }

    // ---- boolean hooks: true = feature enabled, so the caller must short-circuit ----
    public static boolean story() { return flag(0); }
    public static boolean dm() { return flag(1); }
    public static boolean typing() { return flag(2); }
    public static boolean ads() { return flag(3); }

    private static boolean flag(int i) {
        try {
            ensureLoaded(null);
            if (ON[i]) {
                HITS[i]++;
                return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    static boolean isOn(int i) { return ON[i]; }

    /** Count an event of a screen-level feature (ui ad hidden, ...). */
    static void bump(int i) { HITS[i]++; }

    static int hits(int i) { return HITS[i]; }

    static void set(Context c, int i, boolean value) {
        ON[i] = value;
        prefs(c).edit().putBoolean("on_" + KEYS[i], value).apply();
    }

    static void resetHits(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int i = 0; i < HITS.length; i++) {
            HITS[i] = 0;
            e.putInt("hits_" + KEYS[i], 0);
        }
        e.apply();
    }

    static void persistHits(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int i = 0; i < HITS.length; i++) e.putInt("hits_" + KEYS[i], HITS[i]);
        e.apply();
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized void ensureLoaded(Context c) {
        if (loaded) return;
        try {
            if (c == null) {
                Application app = (Application) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                if (app == null) return; // too early; keep defaults, try again on next call
                c = app;
            }
            SharedPreferences sp = prefs(c);
            for (int i = 0; i < KEYS.length; i++) {
                ON[i] = sp.getBoolean("on_" + KEYS[i], true);
                HITS[i] = sp.getInt("hits_" + KEYS[i], 0);
            }
            loaded = true;
        } catch (Throwable ignored) {
        }
    }

    // ---- activity hook: floating draggable button that opens the mod menu ----
    public static void onResume(final Activity a) {
        try {
            ensureLoaded(a);
            persistHits(a);
            View content = a.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            final FrameLayout root = (FrameLayout) content;
            if (root.findViewWithTag(TAG) == null) root.addView(makeButton(a, root));
        } catch (Throwable ignored) {
        }
        try {
            View content2 = a.findViewById(android.R.id.content);
            if (content2 instanceof FrameLayout) UiScanner.attach(a, (FrameLayout) content2);
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static View makeButton(final Activity a, final FrameLayout root) {
        final TextView b = new TextView(a);
        b.setTag(TAG);
        b.setText("M");
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC6C4CFF);
        b.setBackground(bg);
        b.setElevation(dp(a, 8));
        final int size = dp(a, 34);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START);
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        SharedPreferences sp = prefs(a);
        lp.leftMargin = (int) (sp.getFloat("btn_x", 0.02f) * dm.widthPixels);
        lp.topMargin = (int) (sp.getFloat("btn_y", 0.30f) * dm.heightPixels);
        b.setLayoutParams(lp);

        final float slop = dp(a, 6);
        b.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startLeft, startTop;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startLeft = p.leftMargin;
                        startTop = p.topMargin;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
                        if (moved) {
                            p.leftMargin = Math.max(0, Math.min(root.getWidth() - size, startLeft + (int) dx));
                            p.topMargin = Math.max(0, Math.min(root.getHeight() - size, startTop + (int) dy));
                            v.setLayoutParams(p);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            DisplayMetrics m = v.getResources().getDisplayMetrics();
                            prefs(v.getContext()).edit()
                                    .putFloat("btn_x", p.leftMargin / (float) m.widthPixels)
                                    .putFloat("btn_y", p.topMargin / (float) m.heightPixels)
                                    .apply();
                        } else {
                            ModMenu.show(a);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        return b;
    }
}
