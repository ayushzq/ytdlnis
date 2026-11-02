package mod;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Saves the picked CDN url to Movies/ or Pictures/ (InstagramMod folder) through DownloadManager. */
final class Downloader {
    private Downloader() {}

    static void download(Activity a, Object media) {
        try {
            if (media == null) {
                Toast.makeText(a, "No media found for this item (use Inspect screen)", Toast.LENGTH_LONG).show();
                return;
            }
            List<String> urls = Scan.urls(media);
            String url = Scan.pick(urls);
            if (url == null) {
                Toast.makeText(a, "No downloadable URL found (" + urls.size() + " candidates)", Toast.LENGTH_LONG).show();
                return;
            }
            boolean video = Scan.isVideo(url);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String name = "IGMod_" + stamp + (video ? ".mp4" : ".jpg");
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(
                    video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES, "InstagramMod/" + name);
            r.allowScanningByMediaScanner();
            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(r);
            Toast.makeText(a, "Downloading " + (video ? "video" : "photo") + " to InstagramMod", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            try {
                Toast.makeText(a, "Download failed: " + t, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }
}
