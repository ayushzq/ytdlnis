package mod;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** "Inspect screen": writes a text report (view tree, ad detection, Media fields, dev-options probe). */
final class Inspector {
    private Inspector() {}

    static Uri dumpToDownloads(Activity a) {
        String text;
        try {
            text = build(a);
        } catch (Throwable t) {
            text = "inspector failed: " + t;
        }
        try {
            String name = "igmod-inspect-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            ContentResolver cr = a.getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            try (OutputStream os = cr.openOutputStream(uri)) {
                os.write(text.getBytes("UTF-8"));
            }
            return uri;
        } catch (Throwable t) {
            return null;
        }
    }

    static void share(Activity a, Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(i, "Share inspect dump"));
    }

    private static String idName(View v) {
        try {
            if (v.getId() != View.NO_ID) return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String clip(CharSequence c, int n) {
        if (c == null) return "";
        String s = c.toString().replace('\n', ' ');
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    private static void tree(View v, int depth, StringBuilder sb, int[] budget) {
        if (v == null || budget[0]-- <= 0) return;
        Rect r = new Rect();
        v.getGlobalVisibleRect(r);
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < depth; i++) line.append("  ");
        line.append(v.getClass().getName());
        String id = idName(v);
        if (!id.isEmpty()) line.append(" #").append(id);
        if (v instanceof TextView && ((TextView) v).getText().length() > 0) {
            line.append(" text=\"").append(clip(((TextView) v).getText(), 60)).append('"');
        }
        if (v.getContentDescription() != null) line.append(" desc=\"").append(clip(v.getContentDescription(), 50)).append('"');
        if (v.getTag() != null) line.append(" tag=").append(v.getTag().getClass().getName());
        if (v.getVisibility() != View.VISIBLE) line.append(" vis=").append(v.getVisibility());
        line.append(" [").append(v.getWidth()).append('x').append(v.getHeight()).append(']');
        sb.append(line).append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tree(g.getChildAt(i), depth + 1, sb, budget);
        }
    }

    private static void fields(Object o, int depth, int maxDepth, StringBuilder sb, java.util.IdentityHashMap<Object, Boolean> seen) {
        if (o == null || depth > maxDepth || seen.put(o, Boolean.TRUE) != null) return;
        String ind = "";
        for (int i = 0; i < depth; i++) ind += "  ";
        sb.append(ind).append("<").append(o.getClass().getName()).append(">\n");
        String cn = o.getClass().getName();
        if (cn.startsWith("java.") || cn.startsWith("android.")) return;
        int n = 0;
        for (Class<?> k = o.getClass(); k != null && !k.getName().startsWith("java.") && !k.getName().startsWith("android."); k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || n++ > 80) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null) continue;
                    if (v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character) {
                        sb.append(ind).append("  ").append(f.getName()).append(':').append(f.getType().getSimpleName())
                                .append(" = ").append(clip(String.valueOf(v), 110)).append('\n');
                    } else if (v instanceof Iterable) {
                        int c = 0;
                        for (Object e : (Iterable<?>) v) {
                            if (c++ == 0) fields(e, depth + 1, maxDepth, sb, seen);
                        }
                        sb.append(ind).append("  ").append(f.getName()).append(" = list(").append(c).append(")\n");
                    } else if (!(v instanceof View) && !(v instanceof Context)) {
                        sb.append(ind).append("  ").append(f.getName()).append(" ->\n");
                        fields(v, depth + 1, maxDepth, sb, seen);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static String build(Activity a) {
        StringBuilder sb = new StringBuilder();
        sb.append("IG Mod inspect ").append(BuildInfo.VERSION).append('\n');
        sb.append("activity: ").append(a.getClass().getName()).append("\n\n");
        View root = a.findViewById(android.R.id.content);

        sb.append("== lists and rows (leaf RecyclerViews only) ==\n");
        Object firstMedia = null;
        int listNo = 0;
        for (ViewGroup rv : UiScanner.leafRecyclers(root)) {
            sb.append("list ").append(listNo++).append(": ").append(rv.getClass().getName())
                    .append(" #").append(idName(rv)).append(" [").append(rv.getWidth()).append('x').append(rv.getHeight()).append("]\n");
            for (UiScanner.Row row : UiScanner.rowsOf(rv)) {
                Object media = null;
                try {
                    Method m = rv.getClass().getMethod("getChildViewHolder", View.class);
                    media = Scan.findMedia(m.invoke(rv, row.v), 4);
                } catch (Throwable ignored) {
                }
                if (media == null) media = Scan.findMediaInViewTags(row.v, 250);
                if (firstMedia == null && media != null && row.v.isShown()) firstMedia = media;
                sb.append("  pos ").append(row.pos).append(" ").append(row.v.getClass().getSimpleName())
                        .append(" h=").append(row.v.getHeight())
                        .append(" adMarker=").append(UiScanner.hasAdMarker(row.v))
                        .append(" media=").append(media != null)
                        .append(" urls=").append(media == null ? 0 : Scan.urls(media).size()).append('\n');
            }
        }
        if (listNo == 0) sb.append("(no RecyclerView on this screen)\n");

        if (firstMedia != null) {
            sb.append("\n== first visible Media: urls ==\n");
            for (String u : Scan.urls(firstMedia)) sb.append(u.length() > 160 ? u.substring(0, 160) + "..." : u).append('\n');
            sb.append("\n== first visible Media: fields (depth 2) ==\n");
            fields(firstMedia, 0, 2, sb, new java.util.IdentityHashMap<Object, Boolean>());
        }

        sb.append("\n== dev options module probe ==\n");
        try {
            Class<?> c = Class.forName("com.instagram.debug.devoptions.api.DevOptionsModuleLoader");
            sb.append(c.getName()).append(" ctors=").append(c.getDeclaredConstructors().length).append('\n');
            for (Method m : c.getDeclaredMethods()) sb.append("  method ").append(m).append('\n');
            for (Field f : c.getDeclaredFields()) sb.append("  field ").append(f).append('\n');
        } catch (Throwable t) {
            sb.append("not loadable: ").append(t).append('\n');
        }

        sb.append("\n== view tree ==\n");
        tree(root, 0, sb, new int[]{1500});
        return sb.toString();
    }
}
