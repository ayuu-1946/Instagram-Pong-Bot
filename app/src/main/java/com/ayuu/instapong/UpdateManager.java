package com.ayuu.instapong;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class UpdateManager {
    private static final String RELEASE_API = "https://api.github.com/repos/ayuu-1946/Instagram-Pong-Bot/releases/latest";
    private static final String PREFS = "updates";
    private static final String PENDING_ID = "download_id";
    private static BroadcastReceiver receiver;

    private UpdateManager() {}

    public static void check(Activity activity, Runnable onUpdateFound) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                c.setConnectTimeout(7000);
                c.setReadTimeout(7000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Instagram-Pong-Bot");
                if (c.getResponseCode() != 200) return;
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder s = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) s.append(line);
                r.close(); c.disconnect();
                JSONObject release = new JSONObject(s.toString());
                String tag = release.optString("tag_name", "");
                int remote = parseVersion(tag);
                if (remote <= BuildConfig.VERSION_CODE) return;
                String url = release.getJSONArray("assets").getJSONObject(0).getString("browser_download_url");
                activity.runOnUiThread(() -> {
                    onUpdateFound.run();
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("url", url).apply();
                });
            } catch (Exception ignored) {}
        }).start();
    }

    private static int parseVersion(String tag) {
        try { return Integer.parseInt(tag.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    public static void installLatest(Activity activity) {
        String url = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("url", null);
        if (url == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(i);
            return;
        }
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setTitle("Instagram Pong Bot update");
        req.setDescription("Downloading latest bot version");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "InstagramPongBot-update.apk");
        long id = dm.enqueue(req);
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(PENDING_ID, id).apply();
        register(activity);
    }

    public static void register(Activity activity) {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                long pending = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(PENDING_ID, -2);
                if (id != pending) return;
                DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                Uri apk = dm.getUriForDownloadedFile(id);
                if (apk == null) return;
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(apk, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(install);
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(receiver, f);
    }

    public static void unregister(Activity activity) {
        if (receiver != null) {
            try { activity.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiver = null;
        }
    }
}
