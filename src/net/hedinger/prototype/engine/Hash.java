package net.hedinger.prototype.engine;

/**
 * Stateless hashing: a number in [0, 1) that is a pure function of its
 * inputs. Nothing here reads or advances the world's random stream
 * ({@link Utils#random()}), which is the whole point. A draw from the stream
 * depends on everything drawn before it, so two worlds diverge the moment
 * one of them makes an extra draw; a hash depends only on what it was given,
 * so a texture pixel, a cliff lane or a creature's next search heading is the
 * same in every run and in every replay, whatever else happened first.
 *
 * <p>The {@code salt} keeps independent uses apart. Two callers hashing the
 * same coordinates for different purposes pass different salts and get
 * unrelated numbers; the same caller hashing the same point twice, with the
 * same salt, gets the same number. Salts are arbitrary small integers and a
 * caller owns the ones it uses.
 *
 * <p>These are not cryptographic and not meant to be. They are meant to be
 * cheap, replayable and evenly spread, which {@code AHashIsReplayableAndEven}
 * pins.
 */
public final class Hash {

	private Hash() {
	}

	/**
	 * Hash of an integer lattice point and a salt to [0, 1).
	 *
	 * <p>This is the hash the ground textures are drawn with, so its output is
	 * pinned by every tile in the art: change the arithmetic and every grain,
	 * bloom and shard in the world moves. New callers that need more than two
	 * {@code int}s of key use {@link #unit(long, long, int)}.
	 */
	public static double unit(int x, int y, int salt) {
		int h = x * 374761393 + y * 668265263 + salt * (int) 2246822519L;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0x7fffffff) / (double) 0x7fffffff;
	}

	/**
	 * Hash of two 64-bit keys and a salt to [0, 1), for keys that are counters
	 * rather than coordinates -- a tick, a leg index, an entity id -- and so
	 * may outgrow an {@code int}. Each key is mixed with its own odd constant
	 * before the avalanche so that swapping the two keys gives a different
	 * number, and the result takes the top 53 bits so it is a full-precision
	 * double.
	 */
	public static double unit(long a, long b, int salt) {
		long h = a * 0x9E3779B97F4A7C15L;
		h ^= b * 0xC2B2AE3D27D4EB4FL;
		h ^= (salt & 0xFFFFFFFFL) * 0x165667B19E3779F9L;
		h ^= (h >>> 33);
		h *= 0xFF51AFD7ED558CCDL;
		h ^= (h >>> 33);
		h *= 0xC4CEB9FE1A85EC53L;
		h ^= (h >>> 33);
		return (h >>> 11) * (1.0 / (1L << 53));
	}

	/**
	 * {@link #unit(long, long, int)} mapped to [-1, 1): the shape a steering
	 * swing or a signed jitter wants, so callers do not each write
	 * {@code 2 * u - 1}.
	 */
	public static double signed(long a, long b, int salt) {
		return 2 * unit(a, b, salt) - 1;
	}
}
