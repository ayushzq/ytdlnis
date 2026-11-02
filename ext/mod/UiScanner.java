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
 * Screen-level features that do not depend on obfuscated code. Learned from real dumps:
 *  - the feed is Litho: "Ad" is a ComponentHost with contentDescription "Ad", the photo has
 *    contentDescription "Sponsored Photo by ...", one post = several RecyclerView rows (header, media,
 *    CTA, buttons, caption) -> an ad is hidden as a whole group;
 *  - lists are nested (pager RecyclerView -> MainFeedRecyclerView): only "leaf" lists are scanned;
 *  - Reels live in a ViewPager2 (pages must stay match_parent, so an ad page is swiped past);
 *  - Instagram's action icons are Litho hosts (no foreign children), so the download icon is put into the
 *    nearest plain FrameLayout around them: inside the page, moving with it, under dialogs.
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
    private static final WeakHashMap<ViewGroup, Integer> LAST_PAGE = new WeakHashMap<>();
    private static final WeakHashMap<View, PageState> PAGES = new WeakHashMap<>();
    private static final Map<Integer, String> ID_NAMES = new HashMap<>();
    private static final Map<Class<?>, Method> POS_METHODS = new HashMap<>();
    /** row -> adapter position it was judged an ad at (Media carries ad_action / ad_metadata) */
    private static final WeakHashMap<View, Integer> MEDIA_AD = new WeakHashMap<>();
    /** row -> {position, media, timestamp}: avoids re-walking object graphs every pass */
    private static final WeakHashMap<View, Object[]> ROW_MEDIA = new WeakHashMap<>();

    private static final int FEED = 0, REEL = 1, SINGLE = 2;

    static final class Row {
        View v;
        int pos;
    }

    /** An ad page inside a ViewPager2: hidden by alpha only, swiped past, never resized. */
    private static final class PageState {
        int pos;
        long since, lastTry;
        int tries;
        boolean gaveUp;
    }

    private static final class Btn {
        View row;
        ViewGroup rv;
        ViewGroup host;
        int pos;
        int kind;
        View mediaView;
        View anchor;
        DlIconView view;
        int darkState = -1; // 1 dark icon, 0 white icon
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
            if (now - s.lastFull >= 250) {
                s.lastFull = now;
                full(s);
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

    private static boolean plainFrame(Object o) {
        return o instanceof FrameLayout && !o.getClass().getName().startsWith("com.facebook.litho");
    }

    /** Nearest plain FrameLayout above `from` (not Litho hosts, which do not lay out foreign children). */
    private static ViewGroup frameAbove(View from, View stopAt) {
        for (android.view.ViewParent p = from.getParent(); p != null; p = p.getParent()) {
            if (plainFrame(p)) return (ViewGroup) p;
            if (p == stopAt) break;
        }
        return null;
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

    /** The ViewPager2 that owns this RecyclerView, or null. */
    private static ViewGroup pagerOf(ViewGroup rv) {
        android.view.ViewParent p = rv.getParent();
        if (p instanceof ViewGroup) {
            for (Class<?> c = p.getClass(); c != null; c = c.getSuperclass()) {
                if ("androidx.viewpager2.widget.ViewPager2".equals(c.getName())) return (ViewGroup) p;
            }
        }
        return null;
    }

    private static int currentPage(ViewGroup pager) {
        try {
            return (Integer) pager.getClass().getMethod("getCurrentItem").invoke(pager);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void goToPage(ViewGroup pager, int page, boolean smooth) {
        try {
            pager.getClass().getMethod("setCurrentItem", int.class, boolean.class).invoke(pager, page, smooth);
        } catch (Throwable ignored) {
        }
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

    // ---------------------------------------------------------------- finding the Media of a row

    /** {media, source} where source says which strategy found it (shown by Inspect). */
    static Object[] mediaWithSource(ViewGroup rv, View row, int pos) {
        try {
            Method m = rv.getClass().getMethod("getChildViewHolder", View.class);
            Object media = Scan.findMedia(m.invoke(rv, row), 5, 1500);
            if (media != null) return new Object[]{media, "holder"};
        } catch (Throwable ignored) {
        }
        Object media = Scan.findMediaInViewTags(row, 250);
        if (media != null) return new Object[]{media, "view-tags"};
        try {
            Object adapter = rv.getClass().getMethod("getAdapter").invoke(rv);
            media = Scan.mediaFromAdapter(adapter, pos);
            if (media != null) return new Object[]{media, "adapter[" + pos + "]"};
        } catch (Throwable ignored) {
        }
        return new Object[]{null, "none"};
    }

    private static Object mediaOf(ViewGroup rv, View row, int pos) {
        return mediaWithSource(rv, row, pos)[0];
    }

    /** Media of a row, cached briefly (finding it walks an object graph). */
    private static Object cachedMedia(ViewGroup rv, View row, int pos) {
        Object[] c = ROW_MEDIA.get(row);
        long now = SystemClock.uptimeMillis();
        if (c != null && (Integer) c[0] == pos && now - (Long) c[2] < 1500) {
            Object m = ((java.lang.ref.WeakReference<?>) c[1]).get();
            if (m != null) return m;
        }
        Object m = mediaOf(rv, row, pos);
        if (m != null) ROW_MEDIA.put(row, new Object[]{pos, new java.lang.ref.WeakReference<Object>(m), now});
        return m;
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
                Integer mAd = MEDIA_AD.get(row);
                marker[i] = hasAdMarker(row) || (mAd != null && mAd == pos[i]);
                if (header[i] && !feed) {
                    feed = true;
                    FEED_RV.put(rv, Boolean.TRUE);
                }
            }
            boolean[] ads = AdLogic.decide(pos, header, marker, adPos, feed);
            ViewGroup pager = pagerOf(rv);
            if (pager != null) {
                pagerAds(pager, list, pos, ads, hide);
                continue;
            }
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

    /**
     * Reels (ViewPager2). The ad page is made transparent and the pager is moved to the next page (or
     * the previous one if the user is swiping back). Retried with a non-animated jump; if it still
     * cannot move after ~3.5 s the ad is shown again rather than leaving a blank screen.
     */
    private static void pagerAds(ViewGroup pager, List<Row> list, int[] pos, boolean[] ads, boolean hide) {
        int cur = currentPage(pager);
        Integer prev = LAST_PAGE.get(pager);
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < list.size(); i++) {
            View row = list.get(i).v;
            PageState st = PAGES.get(row);
            if (st != null && st.pos != pos[i]) { // page was recycled for another item
                PAGES.remove(row);
                st = null;
                row.setAlpha(1f);
            }
            boolean ad = ads[i] && hide && (st == null || !st.gaveUp);
            if (!ad) {
                if (st != null && !st.gaveUp) PAGES.remove(row);
                if (row.getAlpha() == 0f) row.setAlpha(1f);
                continue;
            }
            if (st == null) {
                st = new PageState();
                st.pos = pos[i];
                st.since = now;
                PAGES.put(row, st);
                Hooks.bump(4);
            }
            row.setAlpha(0f);
            if (cur == pos[i]) {
                if (st.tries < 3 && now - st.lastTry >= 700) {
                    st.tries++;
                    st.lastTry = now;
                    int target = prev != null && prev > cur ? cur - 1 : cur + 1;
                    if (target < 0) target = cur + 1;
                    goToPage(pager, target, st.tries == 1);
                } else if (st.tries >= 3 && now - st.since > 3500) {
                    st.gaveUp = true; // never leave the user on a blank page
                    row.setAlpha(1f);
                }
            }
        }
        if (cur >= 0) LAST_PAGE.put(pager, cur);
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

    private static boolean storyViewerPresent(View root) {
        ArrayDeque<View> q = new ArrayDeque<>();
        q.add(root);
        int budget = 2500;
        while (!q.isEmpty() && budget-- > 0) {
            View v = q.poll();
            if (v.getVisibility() != View.VISIBLE) continue;
            if (idName(v).startsWith("reel_viewer")) return true;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return false;
    }

    private static void full(State s) {
        boolean dl = Hooks.isOn(5), hide = Hooks.isOn(4);
        Set<View> alive = new HashSet<>();
        int rows = 0;
        for (ViewGroup rv : leafRecyclers(s.root)) {
            List<Row> list = rowsOf(rv);
            for (int idx = 0; idx < list.size(); idx++) {
                Row r = list.get(idx);
                View row = r.v;
                if (COLLAPSED.containsKey(row) || !row.isShown()) continue;
                boolean reel = row.getHeight() >= 0.8f * rv.getHeight();
                View big = biggestMedia(row);
                boolean hasBig = big != null && area(big) >= 0.4f * area(row);
                if (!reel && !hasBig) continue; // header/caption pieces of a feed post
                // ad check from the Media's own ad fields (works for Reels ads that carry no label)
                if (hide && visibleFraction(row) > 0.05f) {
                    Object media = cachedMedia(rv, row, r.pos);
                    if (media != null) {
                        String why = Scan.adSignal(media);
                        if (why != null) {
                            if (!MEDIA_AD.containsKey(row)) Hooks.lastAdReason = why;
                            MEDIA_AD.put(row, r.pos);
                        } else {
                            MEDIA_AD.remove(row);
                        }
                    }
                }
                Integer mAd = MEDIA_AD.get(row);
                PageState ps = PAGES.get(row);
                boolean isAd = hide && ((mAd != null && mAd == r.pos) || (ps != null && !ps.gaveUp));
                if (!dl || isAd || visibleFraction(row) < 0.30f) continue;
                rows++;
                alive.add(row);
                View anchor = reel ? findByIdName(row, "like_button") : findFeedSave(list, idx);
                ViewGroup host = reel ? frameAbove(anchor != null ? anchor : row, row) : frameAbove(rv, null);
                if (host == null) host = reel && plainFrame(row) ? (ViewGroup) row : s.root;
                Btn b = s.buttons.get(row);
                if (b == null) {
                    b = newButton(s, row, reel ? REEL : FEED, host);
                    s.buttons.put(row, b);
                } else {
                    moveTo(b, host);
                }
                b.rv = rv;
                b.pos = r.pos;
                b.mediaView = big;
                b.anchor = anchor;
            }
        }
        for (Iterator<Map.Entry<View, Btn>> it = s.buttons.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<View, Btn> e = it.next();
            if (!dl || !alive.contains(e.getKey())) {
                detach(e.getValue());
                it.remove();
            }
        }
        if (!dl) {
            clearButtons(s);
            return;
        }
        // story viewer & other single-media screens: no list rows. The button appears as soon as the
        // viewer exists; the media is resolved on tap (it is bound a little later).
        if (rows == 0) {
            View big = biggestMedia(s.root);
            boolean viewer = big != null && area(big) >= 0.45f * area(s.root)
                    && (storyViewerPresent(s.root) || Scan.findMediaInViewTags(s.root, 1200) != null);
            if (viewer) {
                ViewGroup host = frameAbove(big, null);
                if (host == null) host = s.root;
                if (s.single == null) s.single = newButton(s, s.root, SINGLE, host);
                else moveTo(s.single, host);
                s.single.mediaView = big;
            } else if (s.single != null) {
                detach(s.single);
                s.single = null;
            }
        } else if (s.single != null) {
            detach(s.single);
            s.single = null;
        }
    }

    /** The "Save" icon of the action-bar row that follows a media row. */
    private static View findFeedSave(List<Row> rows, int mediaIdx) {
        for (int i = mediaIdx + 1; i < rows.size() && i <= mediaIdx + 7; i++) {
            View save = findByIdName(rows.get(i).v, "row_feed_button_save");
            if (save != null) return save;
        }
        return null;
    }

    private static void clearButtons(State s) {
        for (Btn b : s.buttons.values()) detach(b);
        s.buttons.clear();
        if (s.single != null) {
            detach(s.single);
            s.single = null;
        }
    }

    private static void detach(Btn b) {
        try {
            if (b.view.getParent() instanceof ViewGroup) ((ViewGroup) b.view.getParent()).removeView(b.view);
        } catch (Throwable ignored) {
        }
        b.host = null;
    }

    private static void moveTo(Btn b, ViewGroup host) {
        if (b.host == host && b.view.getParent() == host) return;
        detach(b);
        b.host = host;
        host.addView(b.view);
    }

    private static boolean lightTheme(Activity a) {
        return (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
    }

    private static boolean expectsVideo(Btn b) {
        return b.kind == REEL || b.mediaView instanceof android.view.TextureView || b.mediaView instanceof android.view.SurfaceView;
    }

    private static Btn newButton(final State s, final View row, final int kind, ViewGroup host) {
        final Btn b = new Btn();
        b.row = row;
        b.kind = kind;
        DlIconView t = new DlIconView(s.a, kind == FEED && lightTheme(s.a));
        t.setTag(TAG_DL);
        int size = Math.round(44 * s.a.getResources().getDisplayMetrics().density);
        t.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START));
        t.setVisibility(View.INVISIBLE);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Downloader.download(s.a, new Downloader.Source() {
                    @Override
                    public Object get() {
                        if (b.kind == SINGLE) {
                            Object m = Scan.findMediaInViewTags(s.root, 2500);
                            return m != null ? m : Scan.mediaFromFragments(s.a);
                        }
                        if (b.rv == null) return Scan.findMediaInViewTags(b.row, 250);
                        return mediaOf(b.rv, b.row, adapterPosition(b.rv, b.row, b.pos));
                    }
                }, expectsVideo(b));
            }
        });
        b.view = t;
        moveTo(b, host);
        return b;
    }

    // ---------------------------------------------------------------- per-frame positioning

    private static void reposition(State s) {
        for (Btn b : s.buttons.values()) place(s, b);
        if (s.single != null) place(s, s.single);
    }

    private static void setIcon(Btn b, boolean dark) {
        int want = dark ? 1 : 0;
        if (b.darkState != want) {
            b.darkState = want;
            b.view.setDark(dark);
        }
    }

    private static void hideBtn(Btn b) {
        b.view.setVisibility(View.INVISIBLE);
    }

    /** All coordinates are relative to the host the button lives in. */
    private static void place(State s, Btn b) {
        ViewGroup host = b.host;
        if (host == null || !host.isShown() || !b.row.isShown()) {
            hideBtn(b);
            return;
        }
        float d = s.a.getResources().getDisplayMetrics().density;
        int size = b.view.getLayoutParams().width;
        int[] o = new int[2];
        host.getLocationOnScreen(o);
        float x, y;
        View anchor = b.anchor;
        boolean anchored = anchor != null && anchor.isAttachedToWindow() && anchor.isShown() && anchor.getWidth() > 0;
        if (b.kind == SINGLE) {
            View ref = b.mediaView != null ? b.mediaView : b.row;
            Rect vis = new Rect();
            if (!ref.isShown() || !ref.getGlobalVisibleRect(vis)) {
                hideBtn(b);
                return;
            }
            x = vis.right - o[0] - size - 6 * d;
            y = vis.top - o[1] + 0.38f * vis.height();
            setIcon(b, false);
        } else if (!anchored) { // Instagram's own icon not found: float over the media instead
            View ref = b.mediaView != null ? b.mediaView : b.row;
            Rect vis = new Rect();
            if (!ref.isShown() || !ref.getGlobalVisibleRect(vis)) {
                hideBtn(b);
                return;
            }
            if (b.kind == REEL) {
                x = vis.left - o[0] + 10 * d;
                y = vis.top - o[1] + 0.38f * vis.height();
            } else {
                x = vis.right - o[0] - size - 6 * d;
                y = vis.bottom - o[1] - size - 6 * d;
            }
            setIcon(b, false);
        } else {
            int[] a = new int[2];
            anchor.getLocationOnScreen(a);
            if (b.kind == REEL) { // directly above the Like button, same column
                x = a[0] - o[0] + (anchor.getWidth() - size) / 2f;
                y = a[1] - o[1] - size - 4 * d;
                setIcon(b, false);
            } else { // feed: left of the Save bookmark, inside the action bar
                x = a[0] - o[0] - size - 2 * d;
                y = a[1] - o[1] + (anchor.getHeight() - size) / 2f;
                setIcon(b, lightTheme(s.a));
            }
        }
        if (y < 0 || y + size > host.getHeight()) {
            hideBtn(b);
            return;
        }
        b.view.setTranslationX(x);
        b.view.setTranslationY(y);
        b.view.setVisibility(View.VISIBLE);
    }
}
