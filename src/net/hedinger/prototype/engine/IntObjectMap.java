package net.hedinger.prototype.engine;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Minimal open-addressing map from primitive {@code int} keys to object values,
 * used for the world's entity store. Avoids the {@code Integer} boxing and
 * {@code Integer.equals} that dominated a dense-tick profile when this was a
 * {@code HashMap<Integer,Entity>} (the per-candidate {@code entities.get(id)}
 * in the search loops).
 *
 * <p>Linear probing, power-of-two capacity. Keys must not equal
 * {@link Integer#MIN_VALUE}, which marks an empty slot (entity IDs are always
 * &ge; 0, so this is safe). There is intentionally no {@code remove}: the world
 * purges dead entities by rebuilding the map, so the table never holds
 * tombstones and {@code get} can stop at the first empty slot.
 */
public final class IntObjectMap<V> {

	private static final int FREE = Integer.MIN_VALUE;
	private static final float LOAD = 0.6f;

	private int[] keys;
	private Object[] values;
	private int size;
	private int mask;
	private int threshold;

	public IntObjectMap() {
		this(1024);
	}

	public IntObjectMap(int expected) {
		int cap = 16;
		while (cap < expected / LOAD) {
			cap <<= 1;
		}
		alloc(cap);
	}

	private void alloc(int cap) {
		keys = new int[cap];
		Arrays.fill(keys, FREE);
		values = new Object[cap];
		mask = cap - 1;
		threshold = (int) (cap * LOAD);
		size = 0;
	}

	private static int spread(int k) {
		return (k ^ (k >>> 16)) & 0x7fffffff;
	}

	public int size() {
		return size;
	}

	@SuppressWarnings("unchecked")
	public V get(int key) {
		int i = spread(key) & mask;
		while (true) {
			int k = keys[i];
			if (k == key) {
				return (V) values[i];
			}
			if (k == FREE) {
				return null;
			}
			i = (i + 1) & mask;
		}
	}

	public void put(int key, V value) {
		int i = spread(key) & mask;
		while (true) {
			int k = keys[i];
			if (k == FREE) {
				keys[i] = key;
				values[i] = value;
				if (++size >= threshold) {
					resize();
				}
				return;
			}
			if (k == key) {
				values[i] = value; // replace, matching HashMap semantics
				return;
			}
			i = (i + 1) & mask;
		}
	}

	private void resize() {
		int[] oldKeys = keys;
		Object[] oldValues = values;
		alloc(oldKeys.length << 1);
		for (int j = 0; j < oldKeys.length; j++) {
			if (oldKeys[j] != FREE) {
				insert(oldKeys[j], oldValues[j]);
			}
		}
	}

	/** Insert during rehash: caller guarantees no duplicate keys, no resize. */
	private void insert(int key, Object value) {
		int i = spread(key) & mask;
		while (keys[i] != FREE) {
			i = (i + 1) & mask;
		}
		keys[i] = key;
		values[i] = value;
		size++;
	}

	/** Live view over the values; values are objects, so no boxing here. */
	public Iterable<V> values() {
		return new Iterable<V>() {
			@Override
			public Iterator<V> iterator() {
				return new ValueIterator();
			}
		};
	}

	private final class ValueIterator implements Iterator<V> {
		private int idx = 0;

		private void advance() {
			while (idx < keys.length && keys[idx] == FREE) {
				idx++;
			}
		}

		@Override
		public boolean hasNext() {
			advance();
			return idx < keys.length;
		}

		@Override
		@SuppressWarnings("unchecked")
		public V next() {
			advance();
			if (idx >= keys.length) {
				throw new NoSuchElementException();
			}
			return (V) values[idx++];
		}
	}
}
