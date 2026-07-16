package net.hedinger.prototype.engine;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tiny bounded LRU cache of rendered sprites keyed by an opaque {@code long}.
 *
 * <p>Deliberately generic and content-agnostic: it does not know what a sprite
 * is or how it is drawn -- callers supply a {@link Renderer} that produces the
 * image on a miss. That keeps the cache orthogonal to visual changes: altering
 * how something is drawn never touches the cache; only the key (which must
 * encode everything that affects the pixels) is the caller's responsibility.
 *
 * <p>Not thread-safe -- intended for the single render thread.
 */
public final class SpriteCache {

	/** Produces a sprite for a cache miss. */
	public interface Renderer {
		BufferedImage render();
	}

	private final int cap;
	private final LinkedHashMap<Long, BufferedImage> map;
	private long hits, misses;

	public SpriteCache(int cap) {
		this.cap = cap;
		this.map = new LinkedHashMap<Long, BufferedImage>(256, 0.75f, true) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, BufferedImage> eldest) {
				return size() > SpriteCache.this.cap;
			}
		};
	}

	/** Returns the cached sprite for {@code key}, rendering + storing it on a miss. */
	public BufferedImage get(long key, Renderer r) {
		BufferedImage b = map.get(key);
		if (b == null) {
			b = r.render();
			map.put(key, b);
			misses++;
		} else {
			hits++;
		}
		return b;
	}

	public int size() {
		return map.size();
	}

	public long hits() {
		return hits;
	}

	public long misses() {
		return misses;
	}

	public void clear() {
		map.clear();
		hits = misses = 0;
	}
}
