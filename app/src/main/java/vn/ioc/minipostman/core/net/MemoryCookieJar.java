package vn.ioc.minipostman.core.net;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/** Cookie jar trong bộ nhớ (sống đến khi đóng app), giữ session giữa các request như Postman. */
public final class MemoryCookieJar implements CookieJar {

    private final List<Cookie> store = new ArrayList<>();

    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        for (Cookie c : cookies) {
            Iterator<Cookie> it = store.iterator();
            while (it.hasNext()) {
                Cookie old = it.next();
                if (old.name().equals(c.name()) && old.domain().equals(c.domain()) && old.path().equals(c.path())) {
                    it.remove();
                }
            }
            if (c.expiresAt() > System.currentTimeMillis()) store.add(c);
        }
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        long now = System.currentTimeMillis();
        List<Cookie> out = new ArrayList<>();
        Iterator<Cookie> it = store.iterator();
        while (it.hasNext()) {
            Cookie c = it.next();
            if (c.expiresAt() <= now) {
                it.remove();
            } else if (c.matches(url)) {
                out.add(c);
            }
        }
        return out;
    }

    public synchronized String value(HttpUrl url, String name) {
        for (Cookie c : loadForRequest(url)) {
            if (c.name().equals(name)) return c.value();
        }
        return null;
    }

    public synchronized void clear() {
        store.clear();
    }
}
