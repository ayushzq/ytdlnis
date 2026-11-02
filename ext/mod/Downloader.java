package mod;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Saves the best CDN url to Movies/ or Pictures/ (InstagramMod). The system shows a "Downloading"
 * progress notification; when it finishes we post a "Download complete" notification with the facts
 * (duration, size, audio, quality, time taken) so a bad/short/silent file is obvious.
 */
final class Downloader {
    private static final String CHANNEL = "igmod_downloads";

    private Downloader() {}

    static void download(final Activity a, Object media) {
        try {
            if (media == null) {
                Toast.makeText(a, "No media found for this item (use Inspect screen)", Toast.LENGTH_LONG).show();
                return;
            }
            List<String> urls = Scan.urls(media);
            final String url = Scan.pick(urls);
            if (url == null) {
                Toast.makeText(a, "No downloadable URL found (" + urls.size() + " candidates)", Toast.LENGTH_LONG).show();
                return;
            }
            final boolean video = Scan.isVideo(url);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            final String name = "IGMod_" + stamp + (video ? ".mp4" : ".jpg");
            final String quality = video ? Scan.describe(url) : "photo";

            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle("Downloading " + (video ? "video" : "photo"));
            r.setDescription(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE); // progress while running
            r.setDestinationInExternalPublicDir(
                    video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES, "InstagramMod/" + name);
            r.allowScanningByMediaScanner();
            final DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            final long id = dm.enqueue(r);
            final long started = SystemClock.elapsedRealtime();
            Hooks.bump(5);
            Toast.makeText(a, "Downloading " + (video ? "video" : "photo") + " (" + quality + ")", Toast.LENGTH_SHORT).show();

            final Context app = a.getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    watch(app, dm, id, name, video, quality, started);
                }
            }, "igmod-dl").start();
        } catch (Throwable t) {
            try {
                Toast.makeText(a, "Download failed: " + t, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void watch(Context ctx, DownloadManager dm, long id, String name, boolean video, String quality, long started) {
        try {
            for (int i = 0; i < 1200; i++) { // up to ~10 minutes
                Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
                try {
                    if (c != null && c.moveToFirst()) {
                        int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            long size = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            Uri uri = dm.getUriForDownloadedFile(id);
                            done(ctx, id, name, video, quality, started, size, uri);
                            return;
                        }
                        if (status == DownloadManager.STATUS_FAILED) {
                            int reason = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON));
                            notify(ctx, (int) id, "Download failed", name + " (error " + reason + ")", null, false);
                            return;
                        }
                    } else {
                        return; // removed by the user
                    }
                } finally {
                    if (c != null) c.close();
                }
                Thread.sleep(500);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String clock(long ms) {
        long s = Math.round(ms / 1000.0);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private static void done(Context ctx, long id, String name, boolean video, String quality, long started, long size, Uri uri) {
        StringBuilder sb = new StringBuilder();
        sb.append(video ? "Video" : "Photo").append(" saved to ").append(video ? "Movies" : "Pictures").append("/InstagramMod");
        sb.append("\n").append(quality);
        if (size > 0) sb.append(" | ").append(String.format(Locale.US, "%.1f MB", size / 1048576.0));
        sb.append(" | took ").append(Math.max(1, (SystemClock.elapsedRealtime() - started) / 1000)).append("s");
        if (video && uri != null) {
            MediaMetadataRetriever m = new MediaMetadataRetriever();
            try {
                m.setDataSource(ctx, uri);
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
        Intent open = null;
        if (uri != null) {
            open = new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(uri, video ? "video/mp4" : "image/jpeg");
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        notify(ctx, (int) id, "Download complete", sb.toString(), open, true);
    }

    private static void notify(Context ctx, int id, String title, String text, Intent open, boolean ok) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Mod downloads", NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                    .setContentTitle(title)
                    .setContentText(text.split("\n")[0])
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true);
            if (open != null) {
                b.setContentIntent(PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            nm.notify(100000 + id, b.build());
        } catch (Throwable ignored) {
        }
    }
}
