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

import java.lang.ref.WeakReference;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Screen-level features that do not depend on obfuscated code. Learned from real dumps and a screen recording:
 *  - the feed is Litho: "Ad" is a ComponentHost with contentDescription "Ad", the photo has contentDescription
 *    "Sponsored Photo by ...", one post = several RecyclerView rows (media, buttons, caption) -> an ad is hidden
 *    as a whole group;
 *  - lists are nested, only "leaf" lists are scanned; Reels live in a ViewPager2 (pages must stay match_parent,
 *    so an ad page is swiped past);
 *  - a tall feed video (87% of the screen) is NOT a Reel: Reel = row of a ViewPager2, and a feed media row is as
 *    wide as the list (story-tray avatars are not);
 *  - cost: one traversal per row (cached ~0.6 s) collects everything, object-graph walks run on a worker thread.
 */
final class UiScanner {
    private static final String TAG_DL = "igmod_dl";
    private static final Set<String> AD_LABELS = new HashSet<>(Arrays.asList(
            "ad", "sponsored", "विज्ञापन", "प्रायोजित", "publicité", "sponsorisé", "anuncio", "patrocinado",
            "werbung", "gesponsert", "sponsorizzato"));

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "igmod-scan");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    });

    private static final WeakHashMap<Activity, State> STATES = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> COLLAPSED = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Set<Integer>> AD_POS = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Boolean> FEED_RV = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Integer> LAST_COUNT = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Integer> LAST_PAGE = new WeakHashMap<>();
    private static final WeakHashMap<View, PageState> PAGES = new WeakHashMap<>();
    private static final WeakHashMap<View, Info> INFO = new WeakHashMap<>();
    private static final Map<Integer, String> ID_NAMES = new HashMap<>();
    private static final Map<Class<?>, Method> POS_METHODS = new HashMap<>();
    /** worker results: row -> media / ad verdict for the position it was computed at */
    private static final Map<View, Found> FOUND = Collections.synchronizedMap(new WeakHashMap<View, Found>());
    private static final Set<View> PENDING = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<View, Boolean>()));

    private static final int FEED = 0, REEL = 1, SINGLE = 2;

    static final class Row {
        View v;
        int pos;
    }

    /** Everything one traversal of a row finds. */
    private static final class Info {
        int pos;
        long t;
        boolean header, marker;
        View big, like, save;
        final List<Object> tags = new ArrayList<>();
    }

    private static final class Found {
        int pos;
        long t;
        WeakReference<Object> media;
        String adReason;
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
        long lastTick, lastLeaves, lastSingle;
        List<ViewGroup> leaves = new ArrayList<>();
        boolean singleOk;
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
                        tick(st);
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
            if (now - s.lastTick >= 120) relayout = tick(s);
            reposition(s);
        } catch (Throwable ignored) {
        }
        return !relayout; // false = redo the frame after rows were collapsed / restored
    }

    // ---------------------------------------------------------------- cheap view helpers

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

    private static List<ViewGroup> leaves(State s, long now) {
        boolean stale = now - s.lastLeaves > 700;
        if (!stale) {
            for (ViewGroup rv : s.leaves) {
                if (!rv.isAttachedToWindow()) {
                    stale = true;
                    break;
                }
            }
        }
        if (stale) {
            s.leaves = leafRecyclers(s.root);
            s.lastLeaves = now;
        }
        return s.leaves;
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

    private static float visibleFraction(View v) {
        Rect r = new Rect();
        if (!v.getGlobalVisibleRect(r) || v.getHeight() == 0) return 0f;
        return r.height() / (float) v.getHeight();
    }

    // ---------------------------------------------------------------- one traversal per row

    private static boolean isAdDesc(CharSequence d) {
        int n = d.length();
        if (n == 2) return (d.charAt(0) | 0x20) == 'a' && (d.charAt(1) | 0x20) == 'd';
        if (n >= 9 && n < 240) {
            String t = d.toString();
            return t.regionMatches(true, 0, "sponsored", 0, 9) && (n == 9 || t.charAt(9) == ' ');
        }
        return false;
    }

    private static Info scanRow(View row) {
        Info info = new Info();
        ArrayDeque<View> q = new ArrayDeque<>();
        q.add(row);
        long bigArea = 0;
        int budget = 700;
        while (!q.isEmpty() && budget-- > 0) {
            View v = q.poll();
            String name = idName(v);
            if (!name.isEmpty()) {
                if (name.equals("row_feed_profile_header")) info.header = true;
                else if (name.equals("like_button") && info.like == null) info.like = v;
                else if (name.equals("row_feed_button_save") && info.save == null) info.save = v;
                else if (name.equals("on_media_sponsored_label") && !(v instanceof ViewStub) && v.getVisibility() == View.VISIBLE) info.marker = true;
            }
            CharSequence d = v.getContentDescription();
            if (d != null && isAdDesc(d)) info.marker = true;
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && cs.length() > 0 && cs.length() <= 14
                        && AD_LABELS.contains(cs.toString().trim().toLowerCase(Locale.ROOT))) info.marker = true;
            }
            if (v != row && v.getVisibility() == View.VISIBLE && (v instanceof ImageView
                    || v instanceof android.view.TextureView || v instanceof android.view.SurfaceView)) {
                long ar = area(v);
                if (ar > bigArea) {
                    bigArea = ar;
                    info.big = v;
                }
            }
            Object tag = v.getTag();
            if (tag != null && info.tags.size() < 8 && !(tag instanceof String) && !(tag instanceof Integer)) info.tags.add(tag);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return info;
    }

    private static Info infoOf(View row, int pos, long now) {
        Info i = INFO.get(row);
        if (i != null && i.pos == pos && now - i.t < 600) return i;
        i = scanRow(row);
        i.pos = pos;
        i.t = now;
        INFO.put(row, i);
        return i;
    }

    /** True if the subtree carries one of Instagram's ad markers (used by Inspect). */
    static boolean hasAdMarker(View row) {
        return scanRow(row).marker;
    }

    // ---------------------------------------------------------------- finding the Media of a row

    /** {media, source}: synchronous version for Inspect and for the tap handler. */
    static Object[] mediaWithSource(ViewGroup rv, View row, int pos) {
        boolean pager = pagerOf(rv) != null;
        try {
            Method m = rv.getClass().getMethod("getChildViewHolder", View.class);
            Object media = Scan.findMedia(m.invoke(rv, row), 5, 1500);
            if (media != null) return new Object[]{media, "holder"};
        } catch (Throwable ignored) {
        }
        Object media = Scan.findMediaInViewTags(row, 250);
        if (media != null) return new Object[]{media, "view-tags"};
        if (pager) { // adapter positions line up with pages only in a pager (a feed post spans several rows)
            try {
                Object adapter = rv.getClass().getMethod("getAdapter").invoke(rv);
                media = Scan.mediaFromAdapter(adapter, pos);
                if (media != null) return new Object[]{media, "adapter[" + pos + "]"};
            } catch (Throwable ignored) {
            }
        }
        return new Object[]{null, "none"};
    }

    /** Ask the worker thread for the Media / ad verdict of a row (results appear in FOUND). */
    private static void request(ViewGroup rv, View row, int pos, Info info, boolean pager, long now) {
        Found f = FOUND.get(row);
        if (f != null && f.pos == pos && now - f.t < 4000) return;
        if (!PENDING.add(row)) return;
        final List<Object> seeds = new ArrayList<>();
        try {
            Method m = rv.getClass().getMethod("getChildViewHolder", View.class);
            Object holder = m.invoke(rv, row);
            if (holder != null) seeds.add(holder);
        } catch (Throwable ignored) {
        }
        seeds.addAll(info.tags);
        Object adapter = null;
        if (pager) {
            try {
                adapter = rv.getClass().getMethod("getAdapter").invoke(rv);
            } catch (Throwable ignored) {
            }
        }
        final Object ad = adapter;
        final int p = pos;
        final View key = row;
        final boolean isPager = pager;
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Object media = null;
                    for (Object seed : seeds) {
                        media = Scan.findMedia(seed, 5, 1500);
                        if (media != null) break;
                    }
                    if (media == null && isPager && ad != null) media = Scan.mediaFromAdapter(ad, p);
                    String why = Scan.adSignal(media);
                    if (why == null && isPager) {
                        for (Object seed : seeds) {
                            why = Scan.sponsoredFlag(seed);
                            if (why != null) break;
                        }
                    }
                    Found r = new Found();
                    r.pos = p;
                    r.t = SystemClock.uptimeMillis();
                    r.media = new WeakReference<>(media);
                    r.adReason = why;
                    if (why != null && (FOUND.get(key) == null || FOUND.get(key).adReason == null)) Hooks.lastAdReason = why;
                    FOUND.put(key, r);
                } catch (Throwable ignored) {
                } finally {
                    PENDING.remove(key);
                }
            }
        });
    }

    private static Found found(View row, int pos) {
        Found f = FOUND.get(row);
        return f != null && f.pos == pos ? f : null;
    }

    // ---------------------------------------------------------------- the pass

    private static boolean mediaRow(ViewGroup rv, View row, Info info, boolean pager) {
        return RowRules.isMediaRow(pager, row.getWidth(), rv.getWidth(),
                info.big == null ? 0 : info.big.getWidth(), info.big == null ? 0 : area(info.big), area(row));
    }

    private static boolean tick(State s) {
        long now = SystemClock.uptimeMillis();
        s.lastTick = now;
        boolean changed = false;
        boolean hide = Hooks.isOn(4), dl = Hooks.isOn(5);
        Set<View> alive = new HashSet<>();
        int rowsWithButtons = 0;

        for (ViewGroup rv : leaves(s, now)) {
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
            ViewGroup pager = pagerOf(rv);
            boolean isPager = pager != null;
            boolean feed = FEED_RV.containsKey(rv);

            List<Row> list = rowsOf(rv);
            int n = list.size();
            int[] pos = new int[n];
            Info[] infos = new Info[n];
            boolean[] header = new boolean[n], marker = new boolean[n], media = new boolean[n];
            for (int i = 0; i < n; i++) {
                View row = list.get(i).v;
                pos[i] = list.get(i).pos;
                infos[i] = infoOf(row, pos[i], now);
                header[i] = infos[i].header;
                media[i] = mediaRow(rv, row, infos[i], isPager);
                if (media[i] && (hide || dl) && visibleFraction(row) > 0.05f) request(rv, row, pos[i], infos[i], isPager, now);
                Found f = found(row, pos[i]);
                marker[i] = infos[i].marker || (f != null && f.adReason != null);
                if (header[i] && !feed) {
                    feed = true;
                    FEED_RV.put(rv, Boolean.TRUE);
                }
            }
            boolean[] ads = AdLogic.decide(pos, header, marker, adPos, feed);

            if (isPager) {
                pagerAds(pager, list, pos, ads, hide);
            } else {
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

            if (!dl) continue;
            for (int i = 0; i < n; i++) {
                View row = list.get(i).v;
                if (!media[i] || COLLAPSED.containsKey(row) || !row.isShown() || visibleFraction(row) < 0.30f) continue;
                PageState ps = PAGES.get(row);
                if (hide && (ads[i] || (ps != null && !ps.gaveUp))) continue; // never put a button on an ad
                Info info = infos[i];
                View anchor = isPager ? info.like : saveAfter(infos, i);
                ViewGroup host;
                if (isPager) {
                    host = frameAbove(anchor != null ? anchor : row, row);
                    if (host == null) host = plainFrame(row) ? (ViewGroup) row : s.root;
                } else {
                    host = frameAbove(rv, null);
                    if (host == null) host = s.root;
                }
                Btn b = s.buttons.get(row);
                if (b == null) {
                    b = newButton(s, row, isPager ? REEL : FEED, host);
                    s.buttons.put(row, b);
                } else {
                    moveTo(b, host);
                }
                b.rv = rv;
                b.pos = pos[i];
                b.mediaView = info.big;
                b.anchor = anchor;
                alive.add(row);
                rowsWithButtons++;
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
            return changed;
        }
        singleMedia(s, rowsWithButtons, now);
        return changed;
    }

    /** The "Save" icon of the action-bar row that follows a media row. */
    private static View saveAfter(Info[] infos, int mediaIdx) {
        for (int i = mediaIdx + 1; i < infos.length && i <= mediaIdx + 7; i++) {
            if (infos[i].save != null) return infos[i].save;
        }
        return null;
    }

    /** Story viewer & other single-media screens: no list rows. */
    private static void singleMedia(State s, int rowsWithButtons, long now) {
        if (rowsWithButtons > 0) {
            if (s.single != null) {
                detach(s.single);
                s.single = null;
            }
            return;
        }
        if (now - s.lastSingle > 500) { // cheap-ish, but not every tick
            s.lastSingle = now;
            Info root = scanRow(s.root);
            boolean viewer = root.big != null && area(root.big) >= 0.45f * area(s.root)
                    && (storyViewerPresent(s.root) || Scan.findMediaInViewTags(s.root, 1200) != null);
            s.singleOk = viewer;
            if (viewer) {
                ViewGroup host = frameAbove(root.big, null);
                if (host == null) host = s.root;
                if (s.single == null) s.single = newButton(s, s.root, SINGLE, host);
                else moveTo(s.single, host);
                s.single.mediaView = root.big;
            }
        }
        if (!s.singleOk && s.single != null) {
            detach(s.single);
            s.single = null;
        }
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

    // ---------------------------------------------------------------- download buttons

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

    /** Media for a tap: the worker's result if it is fresh, otherwise one synchronous search. */
    private static Object mediaForTap(State s, Btn b) {
        if (b.kind == SINGLE) {
            Object m = Scan.findMediaInViewTags(s.root, 2500);
            return m != null ? m : Scan.mediaFromFragments(s.a);
        }
        if (b.rv == null) return Scan.findMediaInViewTags(b.row, 250);
        int pos = adapterPosition(b.rv, b.row, b.pos);
        Found f = found(b.row, pos);
        if (f != null && f.media != null && f.media.get() != null) return f.media.get();
        return mediaWithSource(b.rv, b.row, pos)[0];
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
                        return mediaForTap(s, b);
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
