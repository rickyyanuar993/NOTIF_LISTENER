package com.notiflistener;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LogActivity extends AppCompatActivity {

    private ImageButton btnBack;
    private Button btnClearAllLogs;
    private RecyclerView rvLogs;
    private TextView tvEmptyState;

    private LogDbHelper dbHelper;
    private LogAdapter adapter;
    private List<LogDbHelper.LogRecord> logsList = new ArrayList<>();
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        dbHelper = new LogDbHelper(this);

        btnBack = findViewById(R.id.btnBack);
        btnClearAllLogs = findViewById(R.id.btnClearAllLogs);
        rvLogs = findViewById(R.id.rvLogs);
        tvEmptyState = findViewById(R.id.tvEmptyState);

        btnBack.setOnClickListener(v -> finish());
        btnClearAllLogs.setOnClickListener(v -> confirmClearAll());

        // Setup RecyclerView
        rvLogs.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LogAdapter();
        rvLogs.setAdapter(adapter);

        // Setup Swipe-to-Delete
        setupSwipeToDelete();

        // Load logs
        loadLogs();
    }

    private void loadLogs() {
        executor.execute(() -> {
            final List<LogDbHelper.LogRecord> records = dbHelper.getAllLogs();
            runOnUiThread(() -> {
                logsList.clear();
                logsList.addAll(records);
                adapter.notifyDataSetChanged();
                updateEmptyState();
            });
        });
    }

    private void updateEmptyState() {
        if (logsList.isEmpty()) {
            tvEmptyState.setVisibility(View.VISIBLE);
            rvLogs.setVisibility(View.GONE);
        } else {
            tvEmptyState.setVisibility(View.GONE);
            rvLogs.setVisibility(View.VISIBLE);
        }
    }

    private void confirmClearAll() {
        if (logsList.isEmpty()) {
            Toast.makeText(this, "Log sudah kosong!", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("⚠️ Hapus Semua Log")
                .setMessage("Apakah Anda yakin ingin menghapus seluruh log trafik API secara permanen?")
                .setPositiveButton("Hapus Semua", (dialog, which) -> {
                    executor.execute(() -> {
                        dbHelper.clearAllLogs();
                        runOnUiThread(() -> {
                            logsList.clear();
                            adapter.notifyDataSetChanged();
                            updateEmptyState();
                            Toast.makeText(LogActivity.this, "Semua log berhasil dihapus.", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void setupSwipeToDelete() {
        ItemTouchHelper.SimpleCallback callback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                final int position = viewHolder.getAdapterPosition();
                final LogDbHelper.LogRecord record = logsList.get(position);

                executor.execute(() -> {
                    dbHelper.deleteLog(record.id);
                    runOnUiThread(() -> {
                        logsList.remove(position);
                        adapter.notifyItemRemoved(position);
                        updateEmptyState();
                        Toast.makeText(LogActivity.this, "Log dihapus.", Toast.LENGTH_SHORT).show();
                    });
                });
            }
        };

        ItemTouchHelper helper = new ItemTouchHelper(callback);
        helper.attachToRecyclerView(rvLogs);
    }

    private void showDetailDialog(LogDbHelper.LogRecord record) {
        View dialogView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, null); // dummy fallback
        
        // We will build a beautiful programmatic dialog scroll container because it's customizable and bulletproof.
        ScrollViewContainer scrollContainer = new ScrollViewContainer(this, record);
        
        new AlertDialog.Builder(this)
                .setView(scrollContainer.getView())
                .setPositiveButton("Tutup", null)
                .show();
    }

    // Helper to format JSON payload beautifully
    private String formatJson(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return "";
        try {
            return new JSONObject(rawJson).toString(4);
        } catch (Exception e) {
            return rawJson; // Fallback
        }
    }

    // --- RECYCLERVIEW ADAPTER ---
    private class LogAdapter extends RecyclerView.Adapter<LogViewHolder> {

        @NonNull
        @Override
        public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false);
            return new LogViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
            final LogDbHelper.LogRecord record = logsList.get(position);

            holder.tvAppName.setText(record.appName);
            holder.tvTime.setText(record.timestamp);
            holder.tvDetails.setText(record.title.isEmpty() ? record.text : record.title + ": " + record.text);
            holder.tvApiUrl.setText(record.apiUrl);
            
            // Status Code formatting
            holder.tvStatusCode.setText(String.valueOf(record.statusCode));
            GradientDrawable statusBg = new GradientDrawable();
            statusBg.setCornerRadius(8);

            if (record.statusCode >= 200 && record.statusCode < 300) {
                statusBg.setColor(Color.parseColor("#34D399")); // Green
                holder.tvStatusCode.setTextColor(Color.parseColor("#0F172A"));
                holder.vStatusDot.setBackgroundColor(Color.parseColor("#34D399"));
            } else {
                statusBg.setColor(Color.parseColor("#F43F5E")); // Red
                holder.tvStatusCode.setTextColor(Color.WHITE);
                holder.vStatusDot.setBackgroundColor(Color.parseColor("#F43F5E"));
            }
            holder.tvStatusCode.setBackground(statusBg);

            // Test Badge
            holder.tvTestBadge.setVisibility(record.isTest ? View.VISIBLE : View.GONE);

            // Row click listener
            holder.itemView.setOnClickListener(v -> showDetailDialog(record));
        }

        @Override
        public int getItemCount() {
            return logsList.size();
        }
    }

    private static class LogViewHolder extends RecyclerView.ViewHolder {
        View vStatusDot;
        TextView tvAppName;
        TextView tvTestBadge;
        TextView tvTime;
        TextView tvDetails;
        TextView tvStatusCode;
        TextView tvApiUrl;

        public LogViewHolder(@NonNull View itemView) {
            super(itemView);
            vStatusDot = itemView.findViewById(R.id.vStatusDot);
            tvAppName = itemView.findViewById(R.id.tvLogAppName);
            tvTestBadge = itemView.findViewById(R.id.tvTestBadge);
            tvTime = itemView.findViewById(R.id.tvLogTime);
            tvDetails = itemView.findViewById(R.id.tvLogNotifDetails);
            tvStatusCode = itemView.findViewById(R.id.tvLogStatusCode);
            tvApiUrl = itemView.findViewById(R.id.tvLogApiUrl);
        }
    }

    // Programmatic Custom UI layout builder for Dialog Detail to keep styling look premium
    private class ScrollViewContainer {
        private ScrollView scrollView;
        private LinearLayout mainLayout;

        public ScrollViewContainer(Context context, LogDbHelper.LogRecord record) {
            scrollView = new ScrollView(context);
            scrollView.setBackgroundColor(Color.parseColor("#0F172A"));
            scrollView.setPadding(24, 24, 24, 24);

            mainLayout = new LinearLayout(context);
            mainLayout.setOrientation(LinearLayout.VERTICAL);

            // App title info
            TextView tvTitle = new TextView(context);
            tvTitle.setText(record.appName + " (" + record.packageName + ")");
            tvTitle.setTextColor(Color.parseColor("#E2E8F0"));
            tvTitle.setTextSize(16);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTitle.setPadding(0, 0, 0, 10);
            mainLayout.addView(tvTitle);

            // HTTP Endpoint and Status Row
            LinearLayout rowInfo = new LinearLayout(context);
            rowInfo.setOrientation(LinearLayout.HORIZONTAL);
            rowInfo.setPadding(0, 0, 0, 16);

            TextView tvStatusLabel = new TextView(context);
            tvStatusLabel.setText("Status: " + record.statusCode);
            tvStatusLabel.setTextColor(record.statusCode >= 200 && record.statusCode < 300 ? Color.parseColor("#34D399") : Color.parseColor("#F43F5E"));
            tvStatusLabel.setTextSize(13);
            tvStatusLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            rowInfo.addView(tvStatusLabel);

            TextView tvTimeLabel = new TextView(context);
            tvTimeLabel.setText("  |  " + record.timestamp);
            tvTimeLabel.setTextColor(Color.parseColor("#94A3B8"));
            tvTimeLabel.setTextSize(13);
            rowInfo.addView(tvTimeLabel);

            mainLayout.addView(rowInfo);

            // Section: Target URL
            addSectionHeader(context, "🔗 TARGET URL");
            addMonoText(context, record.apiUrl);

            // Section: Notif Content
            addSectionHeader(context, "💬 ISI NOTIFIKASI");
            addRegularText(context, "Title: " + record.title + "\nText: " + record.text);

            // Section: Request Payload JSON
            addSectionHeaderWithCopy(context, "📥 REQUEST BODY (JSON)", record.reqBody, "Salin Payload");
            addMonoText(context, formatJson(record.reqBody));

            // Section: Response Body
            addSectionHeaderWithCopy(context, "📤 RESPONSE FROM SERVER", record.respBody, "Salin Respons");
            addMonoText(context, record.respBody);

            scrollView.addView(mainLayout);
        }

        public View getView() {
            return scrollView;
        }

        private void addSectionHeader(Context context, String label) {
            TextView tvHeader = new TextView(context);
            tvHeader.setText(label);
            tvHeader.setTextColor(Color.parseColor("#38BDF8"));
            tvHeader.setTextSize(11);
            tvHeader.setTypeface(null, android.graphics.Typeface.BOLD);
            tvHeader.setPadding(0, 16, 0, 6);
            mainLayout.addView(tvHeader);
        }

        private void addSectionHeaderWithCopy(Context context, String label, final String copyText, String copyBtnLabel) {
            LinearLayout sectionRow = new LinearLayout(context);
            sectionRow.setOrientation(LinearLayout.HORIZONTAL);
            sectionRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            sectionRow.setPadding(0, 16, 0, 6);

            TextView tvHeader = new TextView(context);
            tvHeader.setText(label);
            tvHeader.setTextColor(Color.parseColor("#38BDF8"));
            tvHeader.setTextSize(11);
            tvHeader.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tvHeader.setLayoutParams(lp);
            sectionRow.addView(tvHeader);

            TextView tvCopy = new TextView(context);
            tvCopy.setText(copyBtnLabel);
            tvCopy.setTextColor(Color.parseColor("#818CF8"));
            tvCopy.setTextSize(10);
            tvCopy.setPadding(10, 4, 10, 4);
            tvCopy.setBackgroundColor(Color.parseColor("#1E293B"));
            tvCopy.setClickable(true);
            tvCopy.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Copied Text", copyText);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(context, "Berhasil disalin!", Toast.LENGTH_SHORT).show();
                }
            });
            sectionRow.addView(tvCopy);

            mainLayout.addView(sectionRow);
        }

        private void addMonoText(Context context, String text) {
            TextView tv = new TextView(context);
            tv.setText(text == null || text.isEmpty() ? "(Kosong)" : text);
            tv.setTextColor(Color.parseColor("#E2E8F0"));
            tv.setTextSize(11);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setBackgroundColor(Color.parseColor("#020617"));
            tv.setPadding(12, 12, 12, 12);
            mainLayout.addView(tv);
        }

        private void addRegularText(Context context, String text) {
            TextView tv = new TextView(context);
            tv.setText(text);
            tv.setTextColor(Color.parseColor("#E2E8F0"));
            tv.setTextSize(12);
            tv.setBackgroundColor(Color.parseColor("#1E293B"));
            tv.setPadding(12, 12, 12, 12);
            mainLayout.addView(tv);
        }
    }
}
