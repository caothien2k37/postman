package vn.ioc.minipostman.data;

import android.content.Context;
import android.content.SharedPreferences;

import vn.ioc.minipostman.core.net.HttpSettings;

/** Cài đặt chung (timeout, redirect, SSL, pm.sendRequest) lưu trong SharedPreferences. */
public final class AppSettings {

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public HttpSettings load() {
        HttpSettings s = new HttpSettings();
        s.connectTimeoutSec = clamp(prefs.getInt("connect_timeout", 15), 1, 300);
        s.readTimeoutSec = clamp(prefs.getInt("read_timeout", 60), 1, 600);
        s.followRedirects = prefs.getBoolean("follow_redirects", true);
        s.verifySsl = prefs.getBoolean("verify_ssl", true);
        s.allowScriptRequests = prefs.getBoolean("allow_script_requests", true);
        return s;
    }

    public void save(HttpSettings s) {
        prefs.edit()
                .putInt("connect_timeout", clamp(s.connectTimeoutSec, 1, 300))
                .putInt("read_timeout", clamp(s.readTimeoutSec, 1, 600))
                .putBoolean("follow_redirects", s.followRedirects)
                .putBoolean("verify_ssl", s.verifySsl)
                .putBoolean("allow_script_requests", s.allowScriptRequests)
                .apply();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
