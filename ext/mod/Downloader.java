package mod;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads from inside the app process (no DownloadManager): every candidate link is tried in
 * order, a link that returns an error or is not really an mp4 is skipped, the file is written
 * straight into MediaStore (so the Gallery lists it immediately) and the result - or the exact
 * reason for a failure - is shown as a notification and a toast.
 */
final class Downloader {
    private static final String CHANNEL = "igmod_downloads";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger IDS = new AtomicInteger(1);

    private Downloader() {}

    /** Resolves the Media lazily: stories / reels bind it a moment after the button is shown. */
    interface Source {
        Object get();
    }

    static void download(final Activity a, final Source src, final boolean expectVideo) {
        attempt(a, src, expectVideo, 0);
    }

    private static void attempt(final Activity a, final Source src, final boolean expectVideo, final int n) {
        Object media = null;
        try {
            media = src.get();
        } catch (Throwable ignored) {
        }
        if (media != null) {
            download(a, media, expectVideo);
            return;
        }
        if (n >= 10) {
            Toast.makeText(a, "No media found for this item (use Inspect screen)", Toast.LENGTH_LONG).show();
            return;
        }
        if (n == 0) Toast.makeText(a, "Finding media...", Toast.LENGTH_SHORT).show();
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                attempt(a, src, expectVideo, n + 1);
            }
        }, 200);
    }

    static void download(final Activity a, Object media, final boolean expectVideo) {
        try {
            final List<String> urls = Scan.urls(media);
            final List<String> candidates = Scan.candidates(urls, expectVideo);
            if (candidates.isEmpty()) {
                Toast.makeText(a, (expectVideo ? "Video link not found" : "Media link not found") + " (" + urls.size()
                        + " links seen). Use Inspect screen.", Toast.LENGTH_LONG).show();
                return;
            }
            final Context app = a.getApplicationContext();
            final int id = IDS.getAndIncrement();
            Hooks.bump(5);
            String first = candidates.get(0);
            Toast.makeText(a, "Downloading " + (Scan.isVideo(first) ? "video (" + Scan.describe(first) + ")" : "photo") + "...",
                    Toast.LENGTH_SHORT).show();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runAll(app, id, candidates);
                }
            }, "igmod-dl").start();
        } catch (Throwable t) {
            try {
                Toast.makeText(a, "Download failed: " + t, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ worker

    private static final class Result {
        boolean ok, fatal;
        String error = "";
        Uri uri;
        long size;
        String name;
    }

    private static void runAll(Context ctx, int id, List<String> candidates) {
        long started = SystemClock.elapsedRealtime();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String lastError = "no link worked";
        int tried = 0;
        for (String url : candidates) {
            if (tried >= 4) break;
            tried++;
            boolean video = Scan.isVideo(url);
            for (int attempt = 0; attempt < 2; attempt++) {
                Result r = fetch(ctx, id, url, video, "IGMod_" + stamp + (video ? ".mp4" : ".jpg"));
                if (r.ok) {
                    finish(ctx, id, r, video, video ? Scan.describe(url) : "photo", started);
                    return;
                }
                lastError = r.error;
                if (r.fatal) break; // 4xx / not an mp4: retrying the same link is pointless
            }
        }
        cancelProgress(ctx, id);
        String msg = lastError + " (tried " + tried + " link" + (tried == 1 ? "" : "s") + ")";
        notify(ctx, id, "Download failed", msg, null, false);
        toastMain(ctx, "Download failed: " + msg);
    }

    private static Result fail(String error, boolean fatal) {
        Result r = new Result();
        r.error = error;
        r.fatal = fatal;
        return r;
    }

    private static Result fetch(Context ctx, int id, String url, boolean video, String name) {
        HttpURLConnection c = null;
        Uri uri = null;
        ContentResolver cr = ctx.getContentResolver();
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code != 200 && code != 206) return fail("HTTP " + code, code >= 400 && code < 500);
            String type = c.getContentType();
            long total = c.getContentLengthLong();
            InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
            byte[] head = new byte[12];
            int hn = 0;
            while (hn < head.length) {
                int r = in.read(head, hn, head.length - hn);
                if (r < 0) break;
                hn += r;
            }
            if (video && !(hn >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p')) {
                return fail("link is not an mp4 (" + type + ")", true);
            }
            if (hn < 12) return fail("empty response", false);

            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "image/jpeg");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? "Movies" : "Pictures") + "/InstagramMod");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = cr.insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return fail("could not create the file in MediaStore", false);
            OutputStream out = cr.openOutputStream(uri, "w");
            long done = 0, lastUi = 0;
            byte[] buf = new byte[1 << 16];
            out.write(head, 0, hn);
            done += hn;
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                done += n;
                long now = SystemClock.elapsedRealtime();
                if (now - lastUi > 400) {
                    lastUi = now;
                    progress(ctx, id, name, done, total);
                }
            }
            out.flush();
            out.close();
            if (total > 0 && done < total) {
                cr.delete(uri, null, null);
                return fail("connection dropped at " + (done * 100 / total) + "%", false);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, ready, null, null);
            Result ok = new Result();
            ok.ok = true;
            ok.uri = uri;
            ok.size = done;
            ok.name = name;
            return ok;
        } catch (Throwable t) {
            if (uri != null) {
                try {
                    cr.delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            String m = t.getMessage();
            return fail(t.getClass().getSimpleName() + (m == null ? "" : ": " + m), false);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ UI

    private static String clock(long ms) {
        long s = Math.round(ms / 1000.0);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private static void toastMain(final Context ctx, final String text) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void finish(Context ctx, int id, Result r, boolean video, String quality, long started) {
        cancelProgress(ctx, id);
        StringBuilder sb = new StringBuilder();
        sb.append(video ? "Video" : "Photo").append(" saved to ").append(video ? "Movies" : "Pictures")
                .append("/InstagramMod (already in Gallery)");
        sb.append("\n").append(quality);
        sb.append(" | ").append(String.format(Locale.US, "%.1f MB", r.size / 1048576.0));
        sb.append(" | took ").append(Math.max(1, (SystemClock.elapsedRealtime() - started) / 1000)).append("s");
        if (video) {
            MediaMetadataRetriever m = new MediaMetadataRetriever();
            try {
                m.setDataSource(ctx, r.uri);
                String dur = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String audio = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
                String w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                sb.append("\nlength ").append(dur == null ? "?" : clock(Long.parseLong(dur)));
                if (w != null && h != null) sb.append(" | ").append(w).append("x").append(h);
                sb.append(" | audio: ").append("yes".equals(audio) ? "yes" : "NO");
            } catch (Throwable ignored) {
            } finally {
                try {
                    m.release();
                } catch (Throwable ignored) {
                }
            }
        }
        Intent open = new Intent(Intent.ACTION_VIEW);
        open.setDataAndType(r.uri, video ? "video/mp4" : "image/jpeg");
        open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        notify(ctx, id, "Saved: " + r.name, sb.toString(), open, true);
        toastMain(ctx, sb.toString());
    }

    private static NotificationManager manager(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Mod downloads", NotificationManager.IMPORTANCE_DEFAULT));
        return nm;
    }

    private static void progress(Context ctx, int id, String name, long done, long total) {
        try {
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Downloading " + name)
                    .setContentText(total > 0 ? (done * 100 / total) + "%  -  " + String.format(Locale.US, "%.1f / %.1f MB", done / 1048576.0, total / 1048576.0)
                            : String.format(Locale.US, "%.1f MB", done / 1048576.0))
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setProgress(100, total > 0 ? (int) (done * 100 / total) : 0, total <= 0);
            manager(ctx).notify(200000 + id, b.build());
        } catch (Throwable ignored) {
        }
    }

    private static void cancelProgress(Context ctx, int id) {
        try {
            manager(ctx).cancel(200000 + id);
        } catch (Throwable ignored) {
        }
    }

    private static void notify(Context ctx, int id, String title, String text, Intent open, boolean ok) {
        try {
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                    .setContentTitle(title)
                    .setContentText(text.split("\n")[0])
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true);
            if (open != null) {
                b.setContentIntent(PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            manager(ctx).notify(100000 + id, b.build());
        } catch (Throwable ignored) {
        }
    }
}
