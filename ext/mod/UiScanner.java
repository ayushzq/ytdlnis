package mod;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Screen-level features that do not depend on obfuscated code:
 *  - hides feed/reels rows labelled "Ad" / "Sponsored" (collapses them to zero height)
 *  - puts a download button on every visible post / reel row (and on the story viewer)
 */
final class UiScanner {
    private static final String TAG_DL = "igmod_dl";
    private static final Set<String> AD_LABELS = new HashSet<>(Arrays.asList(
            "ad", "sponsored", "विज्ञापन", "प्रायोजित", "publicité", "sponsorisé", "anuncio", "patrocinado",
            "werbung", "gesponsert", "sponsorizzato"));

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, State> STATES = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> COLLAPSED = new WeakHashMap<>();

    private static final class Btn {
        View row;
        TextView view;
        View mediaView;
        boolean single;
    }

    private static final class State {
        Activity a;
        FrameLayout root;
        ViewTreeObserver vto;
        long lastAd, lastFull;
        final Map<View, Btn> buttons = new LinkedHashMap<>();
        Btn single;
        boolean looping;
    }

    private UiScanner() {}

    static void attach(final Activity a, FrameLayout root) {
        State s = STATES.get(a);
        if (s == null) {
            s = new State();
            s.a = a;
            STATES.put(a, s);
        }
        s.root = root;
        final State st = s;
        ViewTreeObserver vto = root.getViewTreeObserver();
        if (st.vto != vto || !vto.isAlive()) {
            if (vto.isAlive()) {
                vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        return onFrame(st);
                    }
                });
                st.vto = vto;
            }
        }
        if (!st.looping) {
            st.looping = true;
            H.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (st.a.isFinishing() || st.a.isDestroyed()) {
                        st.looping = false;
                        return;
                    }
                    try {
                        full(st);
                    } catch (Throwable ignored) {
                    }
                    H.postDelayed(this, 450);
                }
            }, 450);
        }
    }

    // ---------------------------------------------------------------- per frame

    private static boolean onFrame(State s) {
        boolean relayout = false;
        try {
            long now = SystemClock.uptimeMillis();
            if (now - s.lastAd >= 100) {
                s.lastAd = now;
                relayout = adPass(s);
            }
            reposition(s);
        } catch (Throwable ignored) {
        }
        return !relayout; // false = redo the frame once the collapsed rows are laid out
    }

    private static boolean adPass(State s) {
        boolean changed = false;
        boolean hide = Hooks.isOn(4);
        List<ViewGroup> rvs = new ArrayList<>();
        Scan.collectRecyclers(s.root, rvs, new int[]{4000});
        for (ViewGroup rv : rvs) {
            for (int i = 0; i < rv.getChildCount(); i++) {
                View row = rv.getChildAt(i);
                boolean collapsed = COLLAPSED.containsKey(row);
                boolean ad = hide && isAdRow(row);
                if (ad && !collapsed) {
                    ViewGroup.LayoutParams lp = row.getLayoutParams();
                    if (lp != null) {
                        COLLAPSED.put(row, lp.height);
                        lp.height = 0;
                        row.setLayoutParams(lp);
                        row.setVisibility(View.INVISIBLE);
                        Hooks.bump(4);
                        changed = true;
                    }
                } else if (!ad && collapsed) {
                    Integer h = COLLAPSED.remove(row);
                    ViewGroup.LayoutParams lp = row.getLayoutParams();
                    if (lp != null && h != null) {
                        lp.height = h;
                        row.setLayoutParams(lp);
                    }
                    row.setVisibility(View.VISIBLE);
                    changed = true;
                }
            }
        }
        return changed;
    }

    static boolean isAdRow(View row) {
        ArrayDeque<View> q = new ArrayDeque<>();
        q.add(row);
        int budget = 400;
        while (!q.isEmpty() && budget-- > 0) {
            View v = q.poll();
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && cs.length() > 0 && cs.length() <= 14) {
                    if (AD_LABELS.contains(cs.toString().trim().toLowerCase())) return true;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- slow pass (media discovery)

    private static void full(State s) {
        if (!Hooks.isOn(5)) {
            clearButtons(s);
            return;
        }
        List<ViewGroup> rvs = new ArrayList<>();
        Scan.collectRecyclers(s.root, rvs, new int[]{4000});
        Set<View> alive = new HashSet<>();
        int rows = 0;
        for (ViewGroup rv : rvs) {
            for (int i = 0; i < rv.getChildCount(); i++) {
                View row = rv.getChildAt(i);
                if (COLLAPSED.containsKey(row) || !row.isShown() || visibleFraction(row) < 0.30f) continue;
                Object media = mediaForRow(rv, row);
                if (media == null) continue;
                rows++;
                alive.add(row);
                Btn b = s.buttons.get(row);
                if (b == null) {
                    b = newButton(s, row, false);
                    s.buttons.put(row, b);
                }
                b.mediaView = biggestMedia(row);
            }
        }
        for (Iterator<Map.Entry<View, Btn>> it = s.buttons.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<View, Btn> e = it.next();
            if (!alive.contains(e.getKey())) {
                s.root.removeView(e.getValue().view);
                it.remove();
            }
        }
        // story viewer & other single-media screens: no list rows found
        if (rows == 0) {
            View big = biggestMedia(s.root);
            Object media = null;
            if (big != null && area(big) >= 0.45f * area(s.root)) media = Scan.findMediaInViewTags(s.root, 2500);
            if (media != null) {
                if (s.single == null) s.single = newButton(s, s.root, true);
                s.single.mediaView = big;
            } else if (s.single != null) {
                s.root.removeView(s.single.view);
                s.single = null;
            }
        } else if (s.single != null) {
            s.root.removeView(s.single.view);
            s.single = null;
        }
    }

    private static void clearButtons(State s) {
        for (Btn b : s.buttons.values()) s.root.removeView(b.view);
        s.buttons.clear();
        if (s.single != null) {
            s.root.removeView(s.single.view);
            s.single = null;
        }
    }

    private static Object mediaForRow(ViewGroup rv, View row) {
        try {
            Method m = rv.getClass().getMethod("getChildViewHolder", View.class);
            Object holder = m.invoke(rv, row);
            Object media = Scan.findMedia(holder, 4);
            if (media != null) return media;
        } catch (Throwable ignored) {
        }
        return Scan.findMediaInViewTags(row, 250);
    }

    // ---------------------------------------------------------------- geometry & buttons

    private static long area(View v) {
        return (long) v.getWidth() * v.getHeight();
    }

    private static float visibleFraction(View v) {
        Rect r = new Rect();
        if (!v.getGlobalVisibleRect(r) || v.getHeight() == 0) return 0f;
        return r.height() / (float) v.getHeight();
    }

    private static View biggestMedia(View root) {
        View best = null;
        long bestArea = 0;
        ArrayDeque<View> q = new ArrayDeque<>();
        q.add(root);
        int budget = 400;
        while (!q.isEmpty() && budget-- > 0) {
            View v = q.poll();
            if (v != root && (v instanceof ImageView || v instanceof android.view.TextureView
                    || v instanceof android.view.SurfaceView) && v.getVisibility() == View.VISIBLE) {
                long ar = area(v);
                if (ar > bestArea) {
                    bestArea = ar;
                    best = v;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return best;
    }

    private static Btn newButton(final State s, final View row, boolean single) {
        final Btn b = new Btn();
        b.row = row;
        b.single = single;
        TextView t = new TextView(s.a);
        t.setTag(TAG_DL);
        t.setText("\u2193");
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xB3000000);
        bg.setStroke(2, 0x66FFFFFF);
        t.setBackground(bg);
        t.setElevation(8f * s.a.getResources().getDisplayMetrics().density);
        int size = Math.round(38 * s.a.getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START);
        t.setLayoutParams(lp);
        t.setVisibility(View.INVISIBLE);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Object media = null;
                if (b.single) {
                    media = Scan.findMediaInViewTags(s.root, 2500);
                } else {
                    ViewGroup parent = b.row.getParent() instanceof ViewGroup ? (ViewGroup) b.row.getParent() : null;
                    if (parent != null) media = mediaForRow(parent, b.row);
                }
                Downloader.download(s.a, media);
            }
        });
        b.view = t;
        s.root.addView(t);
        return b;
    }

    private static void reposition(State s) {
        for (Btn b : s.buttons.values()) place(s, b);
        if (s.single != null) place(s, s.single);
    }

    private static void place(State s, Btn b) {
        View ref = b.mediaView != null ? b.mediaView : b.row;
        if (!ref.isShown() || !b.row.isShown() || ref.getWidth() == 0) {
            b.view.setVisibility(View.INVISIBLE);
            return;
        }
        float d = s.a.getResources().getDisplayMetrics().density;
        Rect vis = new Rect();
        if (!ref.getGlobalVisibleRect(vis)) {
            b.view.setVisibility(View.INVISIBLE);
            return;
        }
        int[] r = new int[2];
        int[] o = new int[2];
        ref.getLocationOnScreen(r);
        s.root.getLocationOnScreen(o);
        boolean tall = ref.getHeight() > 1.3f * ref.getWidth();
        float x, y;
        if (b.single) {
            x = vis.right - o[0] - 38 * d - 10 * d;
            y = vis.top - o[1] + 0.38f * vis.height();
        } else if (tall) { // reels: free strip on the left edge
            x = vis.left - o[0] + 10 * d;
            y = Math.max(r[1], vis.top) - o[1] + 0.38f * vis.height();
        } else { // feed posts: top-left corner of the media
            x = r[0] - o[0] + 10 * d;
            y = Math.max(r[1], vis.top) - o[1] + 10 * d;
        }
        if (y < vis.top - o[1] || y + 38 * d > vis.bottom - o[1]) {
            b.view.setVisibility(View.INVISIBLE);
            return;
        }
        b.view.setTranslationX(x);
        b.view.setTranslationY(y);
        b.view.setVisibility(View.VISIBLE);
        b.view.bringToFront();
    }
}
