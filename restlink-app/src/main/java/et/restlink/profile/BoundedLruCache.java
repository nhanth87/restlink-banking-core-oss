package et.restlink.profile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded working-set cache. Eldest entry is dropped when {@code max} is exceeded.
 * This is <em>not</em> the profile store — at 1M customers the full set lives in Postgres.
 */
public final class BoundedLruCache<V> {

    private final int max;
    private final LinkedHashMap<String, V> map;

    public BoundedLruCache(int max) {
        this.max = Math.max(1, max);
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > BoundedLruCache.this.max;
            }
        };
    }

    public synchronized V get(String key) { return map.get(key); }

    public synchronized void put(String key, V value) { map.put(key, value); }

    public synchronized void remove(String key) { map.remove(key); }

    public synchronized int size() { return map.size(); }

    public int max() { return max; }
}
