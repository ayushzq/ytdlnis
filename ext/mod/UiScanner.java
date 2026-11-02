package mod;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Screen-level features that do not depend on obfuscated code. Learned from a real dump of the feed:
 *  - the feed is built with Litho: "Ad" is a ComponentHost with contentDescription "Ad", the photo has
 *    contentDescription "Sponsored Photo by ...", and ONE post = several RecyclerView rows
 *    (row_feed_profile_header, media, CTA, buttons, caption) -> an ad is hidden as a whole group;
 *  - lists are nested (pager RecyclerView -> MainFeedRecyclerView), so only "leaf" lists are scanned.
 */
final class UiScanner {
    private static final String TAG_DL = "igmod_dl";
    private static final Set<String> AD_LABELS = new HashSet<>(Arrays.asList(
            "ad", "sponsored", "विज्ञापन", "प्रायोजित", "publicité", "sponsorisé", "anuncio", "patrocinado",
            "werbung", "gesponsert", "sponsorizzato"));

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, State> STATES = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> COLLAPSED = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Set<Integer>> AD_POS = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Boolean> FEED_RV = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Integer> LAST_COUNT = new WeakHashMap<>();
    private static final Map<Integer, String> ID_NAMES = new HashMap<>();
    private static final Map<Class<?>, Method> POS_METHODS = new HashMap<>();

    private static final int FEED = 0, REEL = 1, SINGLE = 2;

    static final class Row {
        View v;
        int pos;
    }

    private static final class Btn {
        View row;
        ViewGroup rv;
        int pos;
        int kind;
        View mediaView;
        View anchor;
        DlIconView view;
    }

    private static final class State {
        Activity a;
        FrameLayout root;
        ViewTreeObserver vto;
        long lastAd;
        final Map<View, Btn> buttons = new LinkedHashMap<>();
        Btn single;
        boolean looping;
    }

    private UiScanner() {}

    // ---------------------------------------------------------------- lifecycle

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
        if ((st.vto != vto || !vto.isAlive()) && vto.isAlive()) {
            vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    return onFrame(st);
                }
            });
            st.vto = vto;
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
        return !relayout; // false = redo the frame after rows were collapsed / restored
    }

    // ---------------------------------------------------------------- view helpers

    static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "";
        String n = ID_NAMES.get(id);
        if (n == null) {
            try {
                n = v.getResources().getResourceEntryName(id);
            } catch (Throwable t) {
                n = "";
            }
            ID_NAMES.put(id, n);
        }
        return n;
    }

    private static View findByIdName(View root, String name) {
        ArrayDeque<View> q = new ArrayDeque<>();
        q.add(root);
        int budget = 700;
        while (!q.isEmpty() && budget-- > 0) {
            View v = q.poll();
            if (name.equals(idName(v))) return v;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return null;
    }

    private static long area(View v) {
        return (long) v.getWidth() * v.getHeight();
    }

    private static boolean isDescendant(View child, View ancestor) {
        for (android.view.ViewParent p = child.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private static void collectAll(View v, List<ViewGroup> out, int[] budget) {
        if (v == null || budget[0]-- <= 0) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (Scan.isRecycler(g)) out.add(g);
            for (int i = 0; i < g.getChildCount(); i++) collectAll(g.getChildAt(i), out, budget);
        }
    }

    /** RecyclerViews that are not just pagers around another big list. */
    static List<ViewGroup> leafRecyclers(View root) {
        List<ViewGroup> all = new ArrayList<>();
        collectAll(root, all, new int[]{6000});
        List<ViewGroup> leaves = new ArrayList<>();
        for (ViewGroup rv : all) {
            boolean wrapper = false;
            for (ViewGroup other : all) {
                if (other != rv && isDescendant(other, rv) && area(other) >= 0.5f * area(rv)) {
                    wrapper = true;
                    break;
                }
            }
            if (!wrapper) leaves.add(rv);
        }
        return leaves;
    }

    private static int adapterPosition(ViewGroup rv, View child, int fallback) {
        try {
            Method m = POS_METHODS.get(rv.getClass());
            if (m == null) {
                m = rv.getClass().getMethod("getChildAdapterPosition", View.class);
                POS_METHODS.put(rv.getClass(), m);
            }
            Object r = m.invoke(rv, child);
            if (r instanceof Integer && (Integer) r >= 0) return (Integer) r;
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int itemCount(ViewGroup rv) {
        try {
            Object ad = rv.getClass().getMethod("getAdapter").invoke(rv);
            if (ad != null) return (Integer) ad.getClass().getMethod("getItemCount").invoke(ad);
        } catch (Throwable ignored) {
        }
        return -1;
    }

    static List<Row> rowsOf(ViewGroup rv) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < rv.getChildCount(); i++) {
            Row r = new Row();
            r.v = rv.getChildAt(i);
            r.pos = adapterPosition(rv, r.v, i);
            rows.add(r);
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row x, Row y) {
                return x.pos - y.pos;
            }
        });
        return rows;
    }

    // ---------------------------------------------------------------- ads

    /** True if the subtree carries one of Instagram's ad markers. */
    static boolean hasAdMarker(View row) {
        ArrayDeque<View> q = new ArrayDeque<>();
        q.add(row);
        int budget = 700;
        while (!q.isEmpty() && budget-- > 0) {
            View v = q.poll();
            CharSequence d = v.getContentDescription();
            if (d != null && d.length() > 0 && d.length() < 240) {
                String s = d.toString().trim().toLowerCase(Locale.ROOT);
                if (s.equals("ad") || s.equals("sponsored") || s.startsWith("sponsored ")) return true;
            }
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && cs.length() > 0 && cs.length() <= 14
                        && AD_LABELS.contains(cs.toString().trim().toLowerCase(Locale.ROOT))) return true;
            }
            if (!(v instanceof ViewStub) && v.getVisibility() == View.VISIBLE && "on_media_sponsored_label".equals(idName(v))) {
                return true;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return false;
    }

    /** Kept for the inspector: does this row (or part of an ad post) look like an ad? */
    static boolean isAdRow(View row) {
        return hasAdMarker(row);
    }

    private static boolean adPass(State s) {
        boolean changed = false;
        boolean hide = Hooks.isOn(4);
        for (ViewGroup rv : leafRecyclers(s.root)) {
            Set<Integer> adPos = AD_POS.get(rv);
            if (adPos == null) {
                adPos = new HashSet<>();
                AD_POS.put(rv, adPos);
            }
            int count = itemCount(rv);
            Integer last = LAST_COUNT.get(rv);
            if (count >= 0) {
                if (last != null && count < last) adPos.clear(); // list was refreshed
                LAST_COUNT.put(rv, count);
            }
            boolean feed = FEED_RV.containsKey(rv);
            List<Row> list = rowsOf(rv);
            int n = list.size();
            int[] pos = new int[n];
            boolean[] header = new boolean[n], marker = new boolean[n];
            for (int i = 0; i < n; i++) {
                View row = list.get(i).v;
                pos[i] = list.get(i).pos;
                header[i] = findByIdName(row, "row_feed_profile_header") != null;
                marker[i] = hasAdMarker(row);
                if (header[i] && !feed) {
                    feed = true;
                    FEED_RV.put(rv, Boolean.TRUE);
                }
            }
            boolean[] ads = AdLogic.decide(pos, header, marker, adPos, feed);
            for (int i = 0; i < n; i++) {
                View row = list.get(i).v;
                boolean ad = ads[i] && hide;
                boolean groupStart = ads[i] && (i == 0 || !ads[i - 1] || pos[i - 1] != pos[i] - 1);
                boolean collapsed = COLLAPSED.containsKey(row);
                if (ad && !collapsed) {
                    ViewGroup.LayoutParams lp = row.getLayoutParams();
                    if (lp != null) {
                        COLLAPSED.put(row, lp.height);
                        lp.height = 0;
                        row.setLayoutParams(lp);
                        row.setVisibility(View.INVISIBLE);
                        if (groupStart) Hooks.bump(4);
                        changed = true;
                    }
                } else if (collapsed && !ad) {
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

    // ---------------------------------------------------------------- download buttons (slow pass)

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
        int budget = 500;
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

    private static Object mediaForRow(ViewGroup rv, View row) {
        try {
            Method m = rv.getClass().getMethod("getChildViewHolder", View.class);
            Object media = Scan.findMedia(m.invoke(rv, row), 4);
            if (media != null) return media;
        } catch (Throwable ignored) {
        }
        return Scan.findMediaInViewTags(row, 250);
    }

    private static void full(State s) {
        if (!Hooks.isOn(5)) {
            clearButtons(s);
            return;
        }
        Set<View> alive = new HashSet<>();
        int rows = 0;
        for (ViewGroup rv : leafRecyclers(s.root)) {
            List<Row> list = rowsOf(rv);
            for (int idx = 0; idx < list.size(); idx++) {
                Row r = list.get(idx);
                View row = r.v;
                if (COLLAPSED.containsKey(row) || !row.isShown() || visibleFraction(row) < 0.30f) continue;
                boolean reel = row.getHeight() >= 0.8f * rv.getHeight();
                View big = biggestMedia(row);
                boolean hasBig = big != null && area(big) >= 0.4f * area(row);
                if (!reel && !hasBig) continue; // header/caption pieces of a feed post: no button
                Object media = mediaForRow(rv, row);
                if (media == null) continue;
                rows++;
                alive.add(row);
                Btn b = s.buttons.get(row);
                if (b == null) {
                    b = newButton(s, row, reel ? REEL : FEED);
                    s.buttons.put(row, b);
                }
                b.rv = rv;
                b.pos = r.pos;
                b.mediaView = big;
                b.anchor = reel ? findByIdName(row, "like_button") : findFeedSave(list, idx);
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
                if (s.single == null) s.single = newButton(s, s.root, SINGLE);
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

    /** The "Save" icon of the action-bar row that follows a media row. */
    private static View findFeedSave(List<Row> rows, int mediaIdx) {
        for (int i = mediaIdx + 1; i < rows.size() && i <= mediaIdx + 4; i++) {
            View save = findByIdName(rows.get(i).v, "row_feed_button_save");
            if (save != null) return save;
        }
        return null;
    }

    private static void clearButtons(State s) {
        for (Btn b : s.buttons.values()) s.root.removeView(b.view);
        s.buttons.clear();
        if (s.single != null) {
            s.root.removeView(s.single.view);
            s.single = null;
        }
    }

    private static boolean lightTheme(Activity a) {
        return (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
    }

    private static Btn newButton(final State s, final View row, final int kind) {
        final Btn b = new Btn();
        b.row = row;
        b.kind = kind;
        // light theme feed icons are dark; Reels / stories are always on dark media -> white
        DlIconView t = new DlIconView(s.a, kind == FEED && lightTheme(s.a));
        t.setTag(TAG_DL);
        int size = Math.round(44 * s.a.getResources().getDisplayMetrics().density);
        t.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START));
        t.setVisibility(View.INVISIBLE);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Object media;
                if (b.kind == SINGLE) {
                    media = Scan.findMediaInViewTags(s.root, 2500);
                } else {
                    media = b.rv != null ? mediaForRow(b.rv, b.row) : Scan.findMediaInViewTags(b.row, 250);
                }
                Downloader.download(s.a, media);
            }
        });
        b.view = t;
        s.root.addView(t);
        return b;
    }

    // ---------------------------------------------------------------- per-frame positioning

    private static void reposition(State s) {
        for (Btn b : s.buttons.values()) place(s, b);
        if (s.single != null) place(s, s.single);
    }

    private static void place(State s, Btn b) {
        float d = s.a.getResources().getDisplayMetrics().density;
        int size = b.view.getLayoutParams().width;
        int[] o = new int[2];
        s.root.getLocationOnScreen(o);
        float x, y;
        View anchor = b.anchor;
        boolean anchored = anchor != null && anchor.isAttachedToWindow() && anchor.isShown() && anchor.getWidth() > 0;
        if (b.kind == SINGLE) {
            View ref = b.mediaView != null ? b.mediaView : b.row;
            Rect vis = new Rect();
            if (!ref.isShown() || !ref.getGlobalVisibleRect(vis)) {
                b.view.setVisibility(View.INVISIBLE);
                return;
            }
            x = vis.right - o[0] - size - 6 * d;
            y = vis.top - o[1] + 0.38f * vis.height();
        } else if (!anchored || !b.row.isShown()) {
            b.view.setVisibility(View.INVISIBLE); // appears together with Instagram's own action icons
            return;
        } else {
            int[] a = new int[2];
            anchor.getLocationOnScreen(a);
            if (b.kind == REEL) { // directly above the Like button, same column
                x = a[0] - o[0] + (anchor.getWidth() - size) / 2f;
                y = a[1] - o[1] - size - 4 * d;
            } else { // feed: left of the Save bookmark, inside the action bar
                x = a[0] - o[0] - size - 2 * d;
                y = a[1] - o[1] + (anchor.getHeight() - size) / 2f;
            }
        }
        if (y < 0 || y + size > s.root.getHeight()) {
            b.view.setVisibility(View.INVISIBLE);
            return;
        }
        b.view.setTranslationX(x);
        b.view.setTranslationY(y);
        b.view.setVisibility(View.VISIBLE);
    }
}
