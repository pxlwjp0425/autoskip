package com.laopeng.autoskip;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class LogStore {

    private static final String KEY = "hit_log";
    private static final int MAX = 200;
    private static LogStore sInstance;

    private final SharedPreferences sp;

    private LogStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("autoskip_log", Context.MODE_PRIVATE);
    }

    public static synchronized LogStore get(Context c) {
        if (sInstance == null) sInstance = new LogStore(c.getApplicationContext());
        return sInstance;
    }

    public synchronized void add(String msg) {
        try {
            JSONArray old = new JSONArray(sp.getString(KEY, "[]"));
            JSONArray out = new JSONArray();
            out.put(DateUtil.stamp() + "  " + msg);
            int n = Math.min(old.length(), MAX - 1);
            for (int i = 0; i < n; i++) {
                out.put(old.optString(i, ""));
            }
            sp.edit().putString(KEY, out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public synchronized List<String> list() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.optString(i, ""));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized void clear() {
        sp.edit().putString(KEY, "[]").apply();
    }
}
