package com.notiflistener;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.graphics.Color;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "NotifListenerPrefs";

    private EditText etApiUrl, etApiHeaders;
    private TextView chipHeaderJson, chipHeaderAuth, chipHeaderApiKey, chipHeaderUserAgent;
    private RadioGroup rgAppFilterMode;
    private RadioButton rbAppAll, rbAppSpecific;
    private LinearLayout layoutSpecificApps;
    private CheckBox chkSourceWa, chkSourceDana, chkSourceOvo;
    private Button btnSelectOtherApps;
    private TextView tvSelectedOtherApps;
    private Switch swKeywordFilterEnabled;
    private LinearLayout layoutKeywordConfig;
    private EditText etKeywords;
    private Switch swAutoClearEnabled;
    private LinearLayout layoutAutoClearConfig;
    private EditText etClearDelay;
    private TextView tvConsoleLog, btnClearConsole;
    private ScrollView scrollConsole;
    private Button btnSaveConfig, btnTestApi;

    private LinearLayout layoutDashboard, layoutGate;
    private Button btnGateGrantNotification;

    private Button btnOpenLogViewer;
    private Button btnOptimizeBattery, btnOptimizeData;
    private TextView tvBatteryStatus;

    private SharedPreferences prefs;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private LogDbHelper dbHelper;

    private Set<String> customSelectedPackages = new LinkedHashSet<>();
    private List<AppRecord> allInstalledApps = new ArrayList<>();

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && intent.hasExtra("log")) {
                String log = intent.getStringExtra("log");
                logConsole(log);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        dbHelper = new LogDbHelper(this);

        // Bind Views
        layoutDashboard = findViewById(R.id.layoutDashboard);
        layoutGate = findViewById(R.id.layoutGate);
        btnGateGrantNotification = findViewById(R.id.btnGateGrantNotification);

        btnOpenLogViewer = findViewById(R.id.btnOpenLogViewer);
        btnOptimizeBattery = findViewById(R.id.btnOptimizeBattery);
        btnOptimizeData = findViewById(R.id.btnOptimizeData);
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus);

        etApiUrl = findViewById(R.id.etApiUrl);
        etApiHeaders = findViewById(R.id.etApiHeaders);
        chipHeaderJson = findViewById(R.id.chipHeaderJson);
        chipHeaderAuth = findViewById(R.id.chipHeaderAuth);
        chipHeaderApiKey = findViewById(R.id.chipHeaderApiKey);
        chipHeaderUserAgent = findViewById(R.id.chipHeaderUserAgent);

        rgAppFilterMode = findViewById(R.id.rgAppFilterMode);
        rbAppAll = findViewById(R.id.rbAppAll);
        rbAppSpecific = findViewById(R.id.rbAppSpecific);
        layoutSpecificApps = findViewById(R.id.layoutSpecificApps);
        chkSourceWa = findViewById(R.id.chkSourceWa);
        chkSourceDana = findViewById(R.id.chkSourceDana);
        chkSourceOvo = findViewById(R.id.chkSourceOvo);
        btnSelectOtherApps = findViewById(R.id.btnSelectOtherApps);
        tvSelectedOtherApps = findViewById(R.id.tvSelectedOtherApps);

        swKeywordFilterEnabled = findViewById(R.id.swKeywordFilterEnabled);
        layoutKeywordConfig = findViewById(R.id.layoutKeywordConfig);
        etKeywords = findViewById(R.id.etKeywords);

        swAutoClearEnabled = findViewById(R.id.swAutoClearEnabled);
        layoutAutoClearConfig = findViewById(R.id.layoutAutoClearConfig);
        etClearDelay = findViewById(R.id.etClearDelay);

        tvConsoleLog = findViewById(R.id.tvConsoleLog);
        btnClearConsole = findViewById(R.id.btnClearConsole);
        scrollConsole = findViewById(R.id.scrollConsole);

        btnSaveConfig = findViewById(R.id.btnSaveConfig);
        btnTestApi = findViewById(R.id.btnTestApi);

        // Setup Listeners
        btnGateGrantNotification.setOnClickListener(v -> openNotificationAccessSettings());
        btnClearConsole.setOnClickListener(v -> {
            tvConsoleLog.setText("");
            logConsole("[System] Console log cleared.");
        });

        rgAppFilterMode.setOnCheckedChangeListener((group, checkedId) -> {
            layoutSpecificApps.setVisibility(checkedId == R.id.rbAppSpecific ? View.VISIBLE : View.GONE);
        });

        swKeywordFilterEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            layoutKeywordConfig.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        swAutoClearEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            layoutAutoClearConfig.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        btnOpenLogViewer.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, LogActivity.class);
            startActivity(intent);
        });

        btnOptimizeBattery.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        btnOptimizeData.setOnClickListener(v -> openDataSaverSettings());

        // Setup quick header chips
        setupHeaderChips();

        btnSelectOtherApps.setOnClickListener(v -> showAppSelectionDialog());
        btnSaveConfig.setOnClickListener(v -> saveConfiguration());
        btnTestApi.setOnClickListener(v -> triggerTestApi());

        // Load configs
        loadConfiguration();

        // Check Permissions
        checkNotificationPermission();

        // Request runtime Notification permission (Android 13+)
        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkNotificationPermission();
        updateBatteryOptimizationStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Register local logger receiver
        registerReceiver(logReceiver, new IntentFilter("com.notiflistener.CONSOLE_LOG"));
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(logReceiver);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    private void updateBatteryOptimizationStatus() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;

        boolean ignoring = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ignoring = pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        if (ignoring) {
            tvBatteryStatus.setText("Status Baterai: 🟢 Tak Terbatas (Optimal)");
            tvBatteryStatus.setTextColor(Color.parseColor("#34D399"));
        } else {
            tvBatteryStatus.setText("Status Baterai: 🔴 Dibatasi (Bisa mati saat idle)");
            tvBatteryStatus.setTextColor(Color.parseColor("#F43F5E"));
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "Aplikasi sudah diatur ke Baterai Tak Terbatas!", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                // Fallback to general settings
                try {
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(intent);
                } catch (Exception ex) {
                    Toast.makeText(this, "Tidak dapat membuka pengaturan baterai.", Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            Toast.makeText(this, "Optimasi baterai tidak diperlukan di versi Android ini.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openDataSaverSettings() {
        // Try opening ignore restrictions page first
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            // Fallback to Data Saver settings
            try {
                Intent intent = new Intent("android.settings.DATA_SAVER_SETTINGS");
                startActivity(intent);
            } catch (Exception ex) {
                // Fallback to Application Info settings
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception exc) {
                    Toast.makeText(this, "Tidak dapat membuka pengaturan data.", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void checkNotificationPermission() {
        boolean granted = isNotificationServiceEnabled();
        if (granted) {
            layoutGate.setVisibility(View.GONE);
            layoutDashboard.setVisibility(View.VISIBLE);
        } else {
            layoutGate.setVisibility(View.VISIBLE);
            layoutDashboard.setVisibility(View.GONE);
        }
    }

    private boolean isNotificationServiceEnabled() {
        String packageName = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(packageName);
    }

    private void openNotificationAccessSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membuka pengaturan. Cari secara manual 'Notification Access'.", Toast.LENGTH_LONG).show();
        }
    }

    private void logConsole(final String text) {
        mainHandler.post(() -> {
            tvConsoleLog.append(text + "\n");
            scrollConsole.post(() -> scrollConsole.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void setupHeaderChips() {
        chipHeaderJson.setOnClickListener(v -> appendHeader("Content-Type: application/json"));
        chipHeaderAuth.setOnClickListener(v -> appendHeader("Authorization: Bearer <TOKEN>"));
        chipHeaderApiKey.setOnClickListener(v -> appendHeader("X-Api-Key: <KEY>"));
        chipHeaderUserAgent.setOnClickListener(v -> appendHeader("User-Agent: NotifListener"));
    }

    private void appendHeader(String headerLine) {
        String currentText = etApiHeaders.getText().toString().trim();
        if (currentText.contains(headerLine.split(":")[0].trim())) {
            Toast.makeText(this, "Header sudah ditambahkan!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentText.isEmpty()) {
            etApiHeaders.setText(headerLine);
        } else {
            etApiHeaders.setText(currentText + "\n" + headerLine);
        }
        // Place cursor at the end
        etApiHeaders.setSelection(etApiHeaders.getText().length());
    }

    private void loadConfiguration() {
        etApiUrl.setText(prefs.getString("api_url", ""));
        etApiHeaders.setText(prefs.getString("api_headers", ""));

        String filterMode = prefs.getString("app_filter_mode", "all");
        if ("all".equals(filterMode)) {
            rbAppAll.setChecked(true);
            layoutSpecificApps.setVisibility(View.GONE);
        } else {
            rbAppSpecific.setChecked(true);
            layoutSpecificApps.setVisibility(View.VISIBLE);
        }

        // Predefined checkboxes
        chkSourceWa.setChecked(prefs.getBoolean("source_wa", true));
        chkSourceDana.setChecked(prefs.getBoolean("source_dana", false));
        chkSourceOvo.setChecked(prefs.getBoolean("source_ovo", false));

        // Custom selected packages
        String savedCustomApps = prefs.getString("target_apps_custom", "");
        customSelectedPackages.clear();
        if (!savedCustomApps.isEmpty()) {
            customSelectedPackages.addAll(Arrays.asList(savedCustomApps.split(",")));
        }
        updateSelectedAppsUI();

        // Keywords
        boolean kwEnabled = prefs.getBoolean("keyword_filter_enabled", false);
        swKeywordFilterEnabled.setChecked(kwEnabled);
        layoutKeywordConfig.setVisibility(kwEnabled ? View.VISIBLE : View.GONE);
        etKeywords.setText(prefs.getString("keywords", ""));

        // Auto Clear
        boolean clearEnabled = prefs.getBoolean("auto_clear_enabled", false);
        swAutoClearEnabled.setChecked(clearEnabled);
        layoutAutoClearConfig.setVisibility(clearEnabled ? View.VISIBLE : View.GONE);
        etClearDelay.setText(String.valueOf(prefs.getInt("clear_delay", 5)));
    }

    private void saveConfiguration() {
        String apiUrl = etApiUrl.getText().toString().trim();
        String apiHeaders = etApiHeaders.getText().toString().trim();
        String filterMode = rbAppAll.isChecked() ? "all" : "specific";
        boolean kwEnabled = swKeywordFilterEnabled.isChecked();
        String keywords = etKeywords.getText().toString().trim();
        boolean clearEnabled = swAutoClearEnabled.isChecked();
        int clearDelay = 5;
        try {
            clearDelay = Integer.parseInt(etClearDelay.getText().toString().trim());
        } catch (NumberFormatException ignored) {}

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("api_url", apiUrl);
        editor.putString("api_headers", apiHeaders);
        editor.putString("app_filter_mode", filterMode);

        editor.putBoolean("source_wa", chkSourceWa.isChecked());
        editor.putBoolean("source_dana", chkSourceDana.isChecked());
        editor.putBoolean("source_ovo", chkSourceOvo.isChecked());

        // Save custom apps list
        StringBuilder sbCustom = new StringBuilder();
        for (String p : customSelectedPackages) {
            if (sbCustom.length() > 0) sbCustom.append(",");
            sbCustom.append(p);
        }
        editor.putString("target_apps_custom", sbCustom.toString());

        // Compile combined set for service to read fast
        Set<String> combinedSet = new HashSet<>();
        if (chkSourceWa.isChecked()) combinedSet.add("com.whatsapp");
        if (chkSourceDana.isChecked()) combinedSet.add("com.id.dana");
        if (chkSourceOvo.isChecked()) combinedSet.add("com.ovo.id");
        combinedSet.addAll(customSelectedPackages);
        editor.putStringSet("target_apps", combinedSet);

        editor.putBoolean("keyword_filter_enabled", kwEnabled);
        editor.putString("keywords", keywords);
        editor.putBoolean("auto_clear_enabled", clearEnabled);
        editor.putInt("clear_delay", clearDelay);

        editor.apply();

        Toast.makeText(this, "Konfigurasi berhasil disimpan!", Toast.LENGTH_SHORT).show();
        logConsole("[System] Konfigurasi disimpan.");
    }

    private void updateSelectedAppsUI() {
        if (customSelectedPackages.isEmpty()) {
            tvSelectedOtherApps.setText("Tidak ada aplikasi tambahan dipilih");
        } else {
            tvSelectedOtherApps.setText("Aplikasi lain: " + String.join(", ", customSelectedPackages));
        }
    }

    private void showAppSelectionDialog() {
        logConsole("[System] Membaca daftar aplikasi...");
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            allInstalledApps.clear();
            for (ApplicationInfo app : apps) {
                // Filter user apps and exclude this app itself
                if (((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) || ((app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)) {
                    if (app.packageName.equals(getPackageName())) continue;
                    // Exclude default checkboxes
                    if (app.packageName.equals("com.whatsapp") ||
                        app.packageName.equals("com.id.dana") ||
                        app.packageName.equals("com.ovo.id")) continue;

                    AppRecord r = new AppRecord();
                    r.packageName = app.packageName;
                    r.label = app.loadLabel(pm).toString();
                    allInstalledApps.add(r);
                }
            }
            // Sort
            Collections.sort(allInstalledApps, (a, b) -> a.label.compareToIgnoreCase(b.label));

            mainHandler.post(() -> {
                if (allInstalledApps.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Tidak ada aplikasi lain ditemukan.", Toast.LENGTH_SHORT).show();
                    return;
                }

                final String[] items = new String[allInstalledApps.size()];
                final boolean[] checkedItems = new boolean[allInstalledApps.size()];
                for (int i = 0; i < allInstalledApps.size(); i++) {
                    AppRecord app = allInstalledApps.get(i);
                    items[i] = app.label + "\n(" + app.packageName + ")";
                    checkedItems[i] = customSelectedPackages.contains(app.packageName);
                }

                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Pilih Aplikasi Tambahan")
                        .setMultiChoiceItems(items, checkedItems, (dialog, which, isChecked) -> {
                            AppRecord app = allInstalledApps.get(which);
                            if (isChecked) {
                                customSelectedPackages.add(app.packageName);
                            } else {
                                customSelectedPackages.remove(app.packageName);
                            }
                        })
                        .setPositiveButton("Simpan", (dialog, which) -> {
                            updateSelectedAppsUI();
                            logConsole("[System] Aplikasi lain terpilih: " + customSelectedPackages);
                        })
                        .setNegativeButton("Batal", null)
                        .show();
            });
        });
    }

    private void triggerTestApi() {
        final String apiUrlStr = etApiUrl.getText().toString().trim();
        final String customHeaders = etApiHeaders.getText().toString().trim();

        if (apiUrlStr.isEmpty()) {
            Toast.makeText(this, "Masukkan API URL terlebih dahulu!", Toast.LENGTH_SHORT).show();
            return;
        }

        logConsole("[Test API] Mengirim payload pengujian ke: " + apiUrlStr);
        executor.execute(() -> {
            HttpURLConnection conn = null;
            String body = "";
            String responseBody = "";
            int responseCode = -1;
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

            try {
                URL url = new URL(apiUrlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);

                // Apply custom headers
                if (!customHeaders.isEmpty()) {
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

                // Create test JSON payload
                JSONObject json = new JSONObject();
                json.put("packageName", "com.notiflistener");
                json.put("appName", "NotifListener Test");
                json.put("title", "Halo User!");
                json.put("text", "Ini adalah payload pengujian dari aplikasi Notif Listener.");
                json.put("postTime", System.currentTimeMillis());
                json.put("id", 999);
                body = json.toString();

                // Write body
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = body.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                responseCode = conn.getResponseCode();
                String timeStampStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                
                if (responseCode >= 200 && responseCode < 300) {
                    StringBuilder response = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line.trim());
                        }
                    }
                    responseBody = response.toString();
                    logConsole("[" + timeStampStr + "] [Test API] SUKSES! Status Code: " + responseCode);
                    
                    // Log in SQLite DB
                    dbHelper.insertLog(timestamp, "NotifListener Test", "com.notiflistener", "Halo User!", "Ini adalah payload pengujian.", apiUrlStr, body, responseCode, responseBody, true);
                } else {
                    StringBuilder errorResponse = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "utf-8"))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            errorResponse.append(line.trim());
                        }
                    } catch (Exception ignored) {}
                    responseBody = errorResponse.toString();
                    logConsole("[" + timeStampStr + "] [Test API] GAGAL! Status Code: " + responseCode + " - " + responseBody);
                    
                    // Log in SQLite DB
                    dbHelper.insertLog(timestamp, "NotifListener Test", "com.notiflistener", "Halo User!", "Ini adalah payload pengujian.", apiUrlStr, body, responseCode, responseBody, true);
                }
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown Error";
                String timeStampStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                logConsole("[" + timeStampStr + "] [Test API] ERROR: " + errorMsg);
                
                // Log in SQLite DB
                dbHelper.insertLog(timestamp, "NotifListener Test", "com.notiflistener", "Halo User!", "Ini adalah payload pengujian.", apiUrlStr, body, -1, "Exception: " + errorMsg, true);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private static class AppRecord {
        String packageName;
        String label;
    }
}
