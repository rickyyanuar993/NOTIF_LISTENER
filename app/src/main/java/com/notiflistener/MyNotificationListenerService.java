package com.notiflistener;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MyNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "NotifListenerService";
    private static final String PREFS_NAME = "NotifListenerPrefs";
    private static final String CHANNEL_ID = "notif_listener_foreground_channel";
    private static final int FOREGROUND_NOTIFICATION_ID = 888;

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private LogDbHelper dbHelper;

    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = new LogDbHelper(this);
        Log.i(TAG, "Notification Listener Service Created");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "Notification Listener Service Connected");
        startForegroundServiceNotification();
    }

    private void startForegroundServiceNotification() {
        // Create Notification Channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Notif Listener Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Menjaga Notif Listener tetap berjalan di latar belakang");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        // Pending Intent to open MainActivity
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        // Build Notification
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        // Get app icon
        int iconRes = getApplicationInfo().icon;
        if (iconRes == 0) {
            iconRes = android.R.drawable.ic_dialog_info;
        }

        Notification notification = builder
                .setContentTitle("Notif Listener Aktif")
                .setContentText("Mendengarkan & meneruskan data notifikasi...")
                .setSmallIcon(iconRes)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        // Start Foreground Q+ compliance
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        FOREGROUND_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                );
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            }
            Log.i(TAG, "Service started in foreground successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service in foreground: " + e.getMessage());
            // Fallback for older/modified devices
            startForeground(FOREGROUND_NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onNotificationPosted(final StatusBarNotification sbn) {
        if (sbn == null) return;

        final String packageName = sbn.getPackageName();
        if (packageName.equals(getPackageName())) {
            // Skip notifications from our own app
            return;
        }

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        final String title = extras.containsKey(Notification.EXTRA_TITLE) ?
                String.valueOf(extras.get(Notification.EXTRA_TITLE)) : "";
        final String text = extras.containsKey(Notification.EXTRA_TEXT) ?
                String.valueOf(extras.get(Notification.EXTRA_TEXT)) : "";

        // Read preferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isEnabled = prefs.getBoolean("service_enabled", true);
        if (!isEnabled) return;

        String appFilterMode = prefs.getString("app_filter_mode", "all");
        if ("specific".equals(appFilterMode)) {
            Set<String> targetApps = prefs.getStringSet("target_apps", new HashSet<>());
            if (!targetApps.contains(packageName)) {
                // Not in target package list
                return;
            }
        }

        // Keyword filter
        boolean keywordFilterEnabled = prefs.getBoolean("keyword_filter_enabled", false);
        if (keywordFilterEnabled) {
            String keywordsStr = prefs.getString("keywords", "").trim();
            if (!keywordsStr.isEmpty()) {
                String[] keywords = keywordsStr.split(",");
                boolean matched = false;
                String lowerTitleText = (title + " " + text).toLowerCase(Locale.getDefault());
                for (String kw : keywords) {
                    String trimmedKw = kw.trim().toLowerCase(Locale.getDefault());
                    if (!trimmedKw.isEmpty() && lowerTitleText.contains(trimmedKw)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    // Did not match any keywords, skip
                    return;
                }
            }
        }

        // Prepare info
        final String appLabel = getAppName(packageName);
        final long postTime = sbn.getPostTime();
        final int notifId = sbn.getId();
        final String key = sbn.getKey();

        // Log locally
        broadcastLog("Captured: " + appLabel + " -> " + title + ": " + text);

        // Forward to API
        final String apiUrl = prefs.getString("api_url", "").trim();
        final String customHeaders = prefs.getString("api_headers", "");
        final boolean autoClear = prefs.getBoolean("auto_clear_enabled", false);
        final int clearDelay = prefs.getInt("clear_delay", 5);

        if (!apiUrl.isEmpty()) {
            executor.execute(() -> {
                boolean apiSuccess = forwardNotificationToApi(apiUrl, customHeaders, packageName, appLabel, title, text, postTime, notifId);
                
                if (apiSuccess && autoClear) {
                    // Delay clear
                    mainHandler.postDelayed(() -> {
                        try {
                            cancelNotification(key);
                            broadcastLog("Dismissed: Notification from " + appLabel + " cleared.");
                        } catch (Exception e) {
                            broadcastLog("Error clearing notification: " + e.getMessage());
                        }
                    }, clearDelay * 1000L);
                }
            });
        } else {
            broadcastLog("Warn: API URL is empty, skipping forward.");
        }
    }

    private boolean forwardNotificationToApi(String apiUrlStr, String customHeaders, String packageName, String appName, String title, String text, long postTime, int id) {
        HttpURLConnection conn = null;
        String reqBody = "";
        String respBody = "";
        int responseCode = -1;
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

        try {
            // Create JSON payload
            JSONObject json = new JSONObject();
            json.put("packageName", packageName);
            json.put("appName", appName);
            json.put("title", title);
            json.put("text", text);
            json.put("postTime", postTime);
            json.put("id", id);
            reqBody = json.toString();

            URL url = new URL(apiUrlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            // Apply custom headers
            if (customHeaders != null && !customHeaders.trim().isEmpty()) {
                String[] lines = customHeaders.split("\n");
                for (String line : lines) {
                    if (line.contains(":")) {
                        int colonIndex = line.indexOf(":");
                        String headerName = line.substring(0, colonIndex).trim();
                        String headerValue = line.substring(colonIndex + 1).trim();
                        if (!headerName.isEmpty()) {
                            conn.setRequestProperty(headerName, headerValue);
                        }
                    }
                }
            }

            // Write body
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = reqBody.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                // Read success stream
                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line.trim());
                    }
                }
                respBody = response.toString();
                broadcastLog("HTTP Success [" + responseCode + "] for " + appName);

                // Insert success log in DB
                dbHelper.insertLog(timestamp, appName, packageName, title, text, apiUrlStr, reqBody, responseCode, respBody, false);
                return true;
            } else {
                // Read error stream
                StringBuilder errorResponse = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "utf-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        errorResponse.append(line.trim());
                    }
                } catch (Exception ignored) {}
                respBody = errorResponse.toString();
                broadcastLog("HTTP Error [" + responseCode + "]: " + respBody);

                // Insert error log in DB
                dbHelper.insertLog(timestamp, appName, packageName, title, text, apiUrlStr, reqBody, responseCode, respBody, false);
                return false;
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown Network Error";
            broadcastLog("Network Error: " + errorMsg);

            // Insert failure log in DB
            dbHelper.insertLog(timestamp, appName, packageName, title, text, apiUrlStr, reqBody, -1, "Exception: " + errorMsg, false);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void broadcastLog(String message) {
        String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String formattedMsg = "[" + timeStamp + "] " + message;
        Log.d(TAG, formattedMsg);

        Intent intent = new Intent("com.notiflistener.CONSOLE_LOG");
        intent.putExtra("log", formattedMsg);
        sendBroadcast(intent);
    }

    private String getAppName(String packageName) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Optional tracking
    }
}
