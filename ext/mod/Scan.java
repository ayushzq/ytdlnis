package mod;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Reflection helpers that work on obfuscated code: find a Media object and its CDN urls. */
final class Scan {
    static final String MEDIA = "com.instagram.feed.media.Media";
    private static final String RV = "androidx.recyclerview.widget.RecyclerView";
    private static final Map<Class<?>, Boolean> RV_CACHE = new HashMap<>();
    private static final Map<Class<?>, Field[]> FIELDS = new HashMap<>();

    private Scan() {}

    static boolean isRecycler(View v) {
        Class<?> c = v.getClass();
        Boolean cached = RV_CACHE.get(c);
        if (cached != null) return cached;
        boolean r = false;
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (RV.equals(k.getName())) {
                r = true;
                break;
            }
        }
        RV_CACHE.put(c, r);
        return r;
    }

    static void collectRecyclers(View v, List<ViewGroup> out, int[] budget) {
        if (v == null || budget[0]-- <= 0) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (isRecycler(g)) {
                out.add(g);
                return; // do not descend into the list itself
            }
            for (int i = 0; i < g.getChildCount(); i++) collectRecyclers(g.getChildAt(i), out, budget);
        }
    }

    private static boolean skipClass(String n) {
        return n.startsWith("android.") || n.startsWith("java.") || n.startsWith("javax.")
                || n.startsWith("kotlin.") || n.startsWith("androidx.") || n.startsWith("dalvik.")
                || n.startsWith("sun.") || n.startsWith("libcore.");
    }

    private static Field[] fieldsOf(Class<?> c) {
        Field[] cached = FIELDS.get(c);
        if (cached != null) return cached;
        List<Field> list = new ArrayList<>();
        for (Class<?> k = c; k != null && !skipClass(k.getName()); k = k.getSuperclass()) {
            try {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        list.add(f);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        Field[] arr = list.toArray(new Field[0]);
        FIELDS.put(c, arr);
        return arr;
    }

    private static boolean bad(Object o) {
        return o instanceof View || o instanceof Context || o instanceof Thread || o instanceof ClassLoader;
    }

    /** Children worth walking: field values (and a few elements of collections / maps / arrays). */
    private static void expand(Object o, List<Object> out) {
        try {
            if (o instanceof Iterable) {
                int n = 0;
                for (Object e : (Iterable<?>) o) {
                    if (e != null && !bad(e)) out.add(e);
                    if (++n >= 8) break;
                }
                return;
            }
            if (o instanceof Map) {
                int n = 0;
                for (Object e : ((Map<?, ?>) o).values()) {
                    if (e != null && !bad(e)) out.add(e);
                    if (++n >= 8) break;
                }
                return;
            }
            if (o instanceof Object[]) {
                Object[] a = (Object[]) o;
                for (int i = 0; i < a.length && i < 8; i++) if (a[i] != null && !bad(a[i])) out.add(a[i]);
                return;
            }
            if (skipClass(o.getClass().getName())) return;
            for (Field f : fieldsOf(o.getClass())) {
                Object v = f.get(o);
                if (v != null && !(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean) && !bad(v)) {
                    out.add(v);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    interface Visitor {
        /** return true to stop */
        boolean visit(Object o, int depth);
    }

    static void walk(Object start, int maxDepth, int maxNodes, Visitor visitor) {
        if (start == null) return;
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Object> q = new ArrayDeque<>();
        ArrayDeque<Integer> d = new ArrayDeque<>();
        q.add(start);
        d.add(0);
        seen.put(start, Boolean.TRUE);
        int count = 0;
        List<Object> kids = new ArrayList<>();
        while (!q.isEmpty() && count++ < maxNodes) {
            Object o = q.poll();
            int depth = d.poll();
            if (visitor.visit(o, depth)) return;
            if (depth >= maxDepth) continue;
            kids.clear();
            expand(o, kids);
            for (Object k : kids) {
                if (seen.put(k, Boolean.TRUE) == null) {
                    q.add(k);
                    d.add(depth + 1);
                }
            }
        }
    }

    static boolean isMedia(Object o) {
        return o != null && MEDIA.equals(o.getClass().getName());
    }

    static Object findMedia(Object start, int maxDepth) {
        return findMedia(start, maxDepth, 400);
    }

    static Object findMedia(Object start, int maxDepth, int maxNodes) {
        final Object[] res = new Object[1];
        walk(start, maxDepth, maxNodes, new Visitor() {
            @Override
            public boolean visit(Object o, int depth) {
                if (isMedia(o)) {
                    res[0] = o;
                    return true;
                }
                return false;
            }
        });
        return res[0];
    }

    /** Looks at the tag of the row and of its descendants (view holders live there). */
    static Object findMediaInViewTags(View row, int budget) {
        ArrayDeque<View> q = new ArrayDeque<>();
        q.add(row);
        while (!q.isEmpty() && budget-- > 0) {
            View v = q.poll();
            Object tag = v.getTag();
            if (tag != null && !(tag instanceof String)) {
                Object m = findMedia(tag, 4);
                if (m != null) return m;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return null;
    }

    /** Media sitting in the adapter's data list at `pos` (fallback when the view holder does not expose it). */
    static Object mediaFromAdapter(Object adapter, final int pos) {
        if (adapter == null || pos < 0) return null;
        final Object[] res = new Object[1];
        walk(adapter, 3, 800, new Visitor() {
            @Override
            public boolean visit(Object o, int depth) {
                if (o instanceof List) {
                    List<?> l = (List<?>) o;
                    if (l.size() > pos && l.get(pos) != null) {
                        Object m = findMedia(l.get(pos), 3, 300);
                        if (m != null) {
                            res[0] = m;
                            return true;
                        }
                    }
                }
                return false;
            }
        });
        return res[0];
    }

    /** Media held by the resumed fragments of the activity (story viewer keeps its state there). */
    static Object mediaFromFragments(Object activity) {
        try {
            Object fm = activity.getClass().getMethod("getSupportFragmentManager").invoke(activity);
            Object frags = fm.getClass().getMethod("getFragments").invoke(fm);
            return mediaFromFragmentList((List<?>) frags, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object mediaFromFragmentList(List<?> frags, int level) {
        for (Object f : frags) {
            try {
                if (f == null) continue;
                Boolean resumed = (Boolean) f.getClass().getMethod("isResumed").invoke(f);
                if (!Boolean.TRUE.equals(resumed)) continue;
                Object m = findMedia(f, 5, 2500);
                if (m != null) return m;
                if (level < 2) {
                    Object cfm = f.getClass().getMethod("getChildFragmentManager").invoke(f);
                    Object kids = cfm.getClass().getMethod("getFragments").invoke(cfm);
                    Object r = mediaFromFragmentList((List<?>) kids, level + 1);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Download candidates, best first. A video post only ever yields video links (never the cover
     * photo), audio-only streams are dropped, progressive mp4 (audio+video in one file) wins.
     */
    static List<String> candidates(List<String> urls, boolean expectVideo) {
        List<String> vids = new ArrayList<>();
        List<String> imgs = new ArrayList<>();
        for (String u : urls) {
            if (isVideo(u)) {
                if (videoScore(u) >= 0) vids.add(u);
            } else {
                imgs.add(u);
            }
        }
        java.util.Collections.sort(vids, new java.util.Comparator<String>() {
            @Override
            public int compare(String x, String y) {
                return Long.compare(videoScore(y), videoScore(x));
            }
        });
        java.util.Collections.sort(imgs, new java.util.Comparator<String>() {
            @Override
            public int compare(String x, String y) {
                return Long.compare(pixels(y), pixels(x));
            }
        });
        List<String> out = new ArrayList<>(vids);
        if (!expectVideo) out.addAll(imgs);
        return out;
    }

    /** CDN urls reachable from a Media object, in discovery order. */
    static List<String> urls(Object media) {
        final List<String> out = new ArrayList<>();
        walk(media, 6, 3000, new Visitor() {
            @Override
            public boolean visit(Object o, int depth) {
                try {
                    if (skipClass(o.getClass().getName())) return false;
                    for (Field f : fieldsOf(o.getClass())) {
                        Object v = f.get(o);
                        if (v instanceof String) addUrl(out, (String) v);
                        else if (v instanceof Iterable) {
                            int n = 0;
                            for (Object e : (Iterable<?>) v) {
                                if (e instanceof String) addUrl(out, (String) e);
                                if (++n > 8) break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return false;
            }
        });
        return out;
    }

    private static void addUrl(List<String> out, String s) {
        if (s.length() < 20 || s.length() > 2500 || !s.startsWith("http")) return;
        if (!(s.contains("cdninstagram") || s.contains("fbcdn"))) return;
        if (!out.contains(s)) out.add(s);
    }

    static boolean isVideo(String url) {
        String path = url.split("\\?")[0].toLowerCase();
        return path.contains(".mp4");
    }

    private static long pixels(String url) {
        long best = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4})x(\\d{3,4})").matcher(url);
        while (m.find()) best = Math.max(best, Long.parseLong(m.group(1)) * Long.parseLong(m.group(2)));
        return best;
    }

    private static Object call(Object o, String name) {
        try {
            return o.getClass().getMethod(name).invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Ad fields live on Instagram's own Media object (build 455: A72() = ad_action, A8b() = ad_metadata).
     * Organic media returns null / empty, ads return values - no "Sponsored" label needed.
     * Returns a short reason or null.
     */
    static String adSignal(Object media) {
        if (media == null) return null;
        Object action = call(media, "A72");
        if (action instanceof String) {
            String a = ((String) action).trim();
            if (!a.isEmpty() && !a.equalsIgnoreCase("none") && !a.equalsIgnoreCase("null")) return "ad_action=" + a;
        }
        Object meta = call(media, "A8b");
        if (meta instanceof java.util.Collection && !((java.util.Collection<?>) meta).isEmpty()) return "ad_metadata";
        return null;
    }

    /** The "vencode_tag" Instagram puts (base64 JSON) in the efg= query parameter, or null. */
    static String encodeTag(String url) {
        try {
            int i = url.indexOf("efg=");
            if (i < 0) return null;
            int j = url.indexOf('&', i);
            String raw = url.substring(i + 4, j < 0 ? url.length() : j);
            raw = java.net.URLDecoder.decode(raw, "UTF-8");
            byte[] bytes;
            try {
                bytes = java.util.Base64.getUrlDecoder().decode(raw);
            } catch (IllegalArgumentException e) {
                bytes = java.util.Base64.getMimeDecoder().decode(raw);
            }
            String json = new String(bytes, "UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"vencode_tag\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Height in pixels read from the encode tag (e.g. ".720." or "1080p"), 0 if unknown. */
    static int heightOf(String tag) {
        if (tag == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[._](\\d{3,4})(?:p)?[._]").matcher(tag);
        int best = 0;
        while (m.find()) best = Math.max(best, Integer.parseInt(m.group(1)));
        return best;
    }

    /** Higher is better; -1 = audio-only stream (never pick it for a video download). */
    static long videoScore(String url) {
        String tag = encodeTag(url);
        if (tag == null) return 0;
        String t = tag.toLowerCase();
        if (t.contains("audio") && !t.contains("video")) return -1;
        long score = heightOf(tag);
        if (t.contains("progressive")) score += 1_000_000L; // progressive mp4 has audio+video in one file
        return score;
    }

    static String describe(String url) {
        String tag = encodeTag(url);
        int h = heightOf(tag);
        String kind = tag != null && tag.toLowerCase().contains("progressive") ? "progressive" : "dash";
        return (h > 0 ? h + "p " : "") + kind;
    }

    /** Best video (highest quality, with audio) if there is one, else the largest-looking image. */
    static String pick(List<String> urls) {
        String bestVideo = null;
        long bestScore = Long.MIN_VALUE;
        for (String u : urls) {
            if (!isVideo(u)) continue;
            long sc = videoScore(u);
            if (sc > bestScore) {
                bestScore = sc;
                bestVideo = u;
            }
        }
        if (bestVideo != null && bestScore >= 0) return bestVideo;
        String best = null;
        long bestPx = -1;
        for (String u : urls) {
            if (isVideo(u)) continue;
            long px = pixels(u);
            if (px > bestPx) {
                bestPx = px;
                best = u;
            }
        }
        return best != null ? best : bestVideo;
    }
}
