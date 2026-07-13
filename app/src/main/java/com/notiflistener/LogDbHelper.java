package com.notiflistener;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class LogDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "notif_listener_logs.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_LOGS = "api_logs";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_TIMESTAMP = "timestamp";
    public static final String COLUMN_APP_NAME = "app_name";
    public static final String COLUMN_PACKAGE_NAME = "package_name";
    public static final String COLUMN_NOTIF_TITLE = "notif_title";
    public static final String COLUMN_NOTIF_TEXT = "notif_text";
    public static final String COLUMN_API_URL = "api_url";
    public static final String COLUMN_REQ_BODY = "req_body";
    public static final String COLUMN_STATUS_CODE = "status_code";
    public static final String COLUMN_RESP_BODY = "resp_body";
    public static final String COLUMN_IS_TEST = "is_test";

    private static final String TABLE_CREATE =
            "CREATE TABLE " + TABLE_LOGS + " (" +
            COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COLUMN_TIMESTAMP + " TEXT, " +
            COLUMN_APP_NAME + " TEXT, " +
            COLUMN_PACKAGE_NAME + " TEXT, " +
            COLUMN_NOTIF_TITLE + " TEXT, " +
            COLUMN_NOTIF_TEXT + " TEXT, " +
            COLUMN_API_URL + " TEXT, " +
            COLUMN_REQ_BODY + " TEXT, " +
            COLUMN_STATUS_CODE + " INTEGER, " +
            COLUMN_RESP_BODY + " TEXT, " +
            COLUMN_IS_TEST + " INTEGER DEFAULT 0" +
            ");";

    public LogDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(TABLE_CREATE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_LOGS);
        onCreate(db);
    }

    public synchronized long insertLog(String timestamp, String appName, String packageName, String title,
                                       String text, String apiUrl, String reqBody, int statusCode,
                                       String respBody, boolean isTest) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_TIMESTAMP, timestamp);
        values.put(COLUMN_APP_NAME, appName);
        values.put(COLUMN_PACKAGE_NAME, packageName);
        values.put(COLUMN_NOTIF_TITLE, title);
        values.put(COLUMN_NOTIF_TEXT, text);
        values.put(COLUMN_API_URL, apiUrl);
        values.put(COLUMN_REQ_BODY, reqBody);
        values.put(COLUMN_STATUS_CODE, statusCode);
        values.put(COLUMN_RESP_BODY, respBody);
        values.put(COLUMN_IS_TEST, isTest ? 1 : 0);

        long id = db.insert(TABLE_LOGS, null, values);
        pruneOldLogs(db); // Prevent logs from building up infinitely
        return id;
    }

    private void pruneOldLogs(SQLiteDatabase db) {
        // Only keep the latest 500 logs to optimize storage
        db.execSQL("DELETE FROM " + TABLE_LOGS + " WHERE " + COLUMN_ID + " NOT IN (" +
                   "SELECT " + COLUMN_ID + " FROM " + TABLE_LOGS +
                   " ORDER BY " + COLUMN_ID + " DESC LIMIT 500);");
    }

    public synchronized List<LogRecord> getAllLogs() {
        List<LogRecord> logsList = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_LOGS, null, null, null, null, null, COLUMN_ID + " DESC");

        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    do {
                        LogRecord record = new LogRecord();
                        record.id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID));
                        record.timestamp = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP));
                        record.appName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_APP_NAME));
                        record.packageName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PACKAGE_NAME));
                        record.title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOTIF_TITLE));
                        record.text = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NOTIF_TEXT));
                        record.apiUrl = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_API_URL));
                        record.reqBody = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_REQ_BODY));
                        record.statusCode = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_STATUS_CODE));
                        record.respBody = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_RESP_BODY));
                        record.isTest = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_TEST)) == 1;
                        logsList.add(record);
                    } while (cursor.moveToNext());
                }
            } finally {
                cursor.close();
            }
        }
        return logsList;
    }

    public synchronized boolean deleteLog(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        return db.delete(TABLE_LOGS, COLUMN_ID + "=" + id, null) > 0;
    }

    public synchronized void clearAllLogs() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_LOGS, null, null);
    }

    public static class LogRecord {
        public long id;
        public String timestamp;
        public String appName;
        public String packageName;
        public String title;
        public String text;
        public String apiUrl;
        public String reqBody;
        public int statusCode;
        public String respBody;
        public boolean isTest;
    }
}
