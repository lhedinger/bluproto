package net.hedinger.prototype.engine;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Procedurally generated ground tile textures. Instead of painting each field
 * as a flat colour, the ground gets a monochrome *pattern* per terrain type --
 * top-down grass (stipple when thin, mottle when lush), water ripples, mud
 * speckle, tall-grass cover -- so terrains are told apart by texture (identity)
 * and a scalar like vegetation density rides on top (magnitude).
 *
 * <p>Grass is split into a flat green {@link #GRASS_GREEN} base (drawn by the
 * caller) plus a transparent pattern overlay, so lush mottle tiles can fade
 * their blob overlay on edges facing thinner neighbours -- the blotches melt
 * into the plain green (which the stipple shares) instead of ending in a hard
 * square. The 16 edge-fade combinations are pre-baked once, from a dedicated
 * {@link Random} that never touches the simulation RNG.
 */
public final class GroundTextures {

	/** Flat grass ground; the caller fills this before the pattern overlay. */
	public static final Color GRASS_GREEN = new Color(46, 104, 54, 205);

	private static final int VARIANTS = 3;
	private static boolean ready = false;

	private static BufferedImage[][] stipple;   // [thin level 0..1][variant]
	private static BufferedImage[][][] mottle;  // [lush level 0..1][variant][edgeMask]
	private static BufferedImage[] water;
	private static BufferedImage[] mud;
	private static BufferedImage[] cover;

	private GroundTextures() {
	}

	public static void ensure() {
		if (ready) {
			return;
		}
		int ts = ResourceManager.tileSize;
		Random rng = new Random(0x6C9A11E5L); // dedicated: not the sim RNG

		stipple = new BufferedImage[2][VARIANTS];
		for (int l = 0; l < 2; l++) {
			for (int v = 0; v < VARIANTS; v++) {
				stipple[l][v] = makeStipple(ts, l == 0 ? 22 : 42, rng);
			}
		}
		mottle = new BufferedImage[2][VARIANTS][16];
		for (int l = 0; l < 2; l++) {
			for (int v = 0; v < VARIANTS; v++) {
				BufferedImage base = makeMottle(ts, l == 0 ? 12 : 20, rng);
				for (int mask = 0; mask < 16; mask++) {
					mottle[l][v][mask] = mask == 0 ? base : fadeEdges(base, mask, ts);
				}
			}
		}
		water = new BufferedImage[VARIANTS];
		mud = new BufferedImage[VARIANTS];
		cover = new BufferedImage[VARIANTS];
		for (int v = 0; v < VARIANTS; v++) {
			water[v] = makeWater(ts, rng);
			mud[v] = makeMud(ts, rng);
			cover[v] = makeCover(ts, rng);
		}
		ready = true;
	}

	/** Grass density level for a vegetation fraction: -1 bare, 0-1 stipple, 2-3 mottle. */
	public static int grassLevel(double veg) {
		if (veg < 0.12) {
			return -1;
		}
		return veg < 0.35 ? 0 : veg < 0.6 ? 1 : veg < 0.85 ? 2 : 3;
	}

	public static boolean isMottle(int level) {
		return level >= 2;
	}

	/**
	 * The transparent grass pattern overlay for a level/variant. Mottle levels
	 * use {@code edgeMask} (bits N=1, E=2, S=4, W=8) to fade the blobs on edges
	 * that face thinner grass; stipple ignores it.
	 */
	public static BufferedImage grassPattern(int level, int variant, int edgeMask) {
		ensure();
		int v = (variant & 0x7fffffff) % VARIANTS;
		if (level <= 1) {
			return stipple[level][v];
		}
		return mottle[level - 2][v][edgeMask & 15];
	}

	/** Opaque texture for the non-grass terrains (water/mud/cover), else null. */
	public static BufferedImage terrain(Tile t, int hash) {
		ensure();
		int v = (hash & 0x7fffffff) % VARIANTS;
		switch (t.getType()) {
		case TYPE_WATER:
			return water[v];
		case TYPE_MUD:
			return mud[v];
		case TYPE_COVER:
			return cover[v];
		default:
			return null;
		}
	}

	// ---- generators --------------------------------------------------------

	private static Graphics2D gfx(BufferedImage img) {
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return g;
	}

	/** Sparse dots as top-down grain, transparent-backed. */
	private static BufferedImage makeStipple(int ts, int count, Random rng) {
		BufferedImage img = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		for (int i = 0; i < count; i++) {
			int x = rng.nextInt(ts), y = rng.nextInt(ts), r = 2 + rng.nextInt(2);
			boolean light = rng.nextBoolean();
			g.setColor(light ? new Color(90, 175, 95, 200) : new Color(28, 74, 38, 200));
			g.fillOval(x, y, r, r);
		}
		g.dispose();
		return img;
	}

	/**
	 * Lush grass as organic light/dark blotches, transparent-backed. Each blob
	 * is drawn at all nine toroidal offsets so it wraps across the tile edges --
	 * the texture tiles with itself, killing the hard seams that clipped blobs
	 * produced.
	 */
	private static BufferedImage makeMottle(int ts, int blobs, Random rng) {
		BufferedImage img = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		for (int i = 0; i < blobs; i++) {
			int x = rng.nextInt(ts), y = rng.nextInt(ts);
			int r = ts / 6 + rng.nextInt(ts / 3);
			boolean light = rng.nextBoolean();
			g.setColor(light ? new Color(84, 168, 92, 80) : new Color(24, 72, 34, 95));
			for (int ox = -1; ox <= 1; ox++) {
				for (int oy = -1; oy <= 1; oy++) {
					g.fillOval(x - r / 2 + ox * ts, y - r / 2 + oy * ts, r, r);
				}
			}
		}
		g.dispose();
		return img;
	}

	/** Multiplies the alpha by an edge ramp on each flagged edge (N,E,S,W). */
	private static BufferedImage fadeEdges(BufferedImage src, int mask, int ts) {
		BufferedImage out = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
		int fade = ts / 4; // ramp width in px
		for (int y = 0; y < ts; y++) {
			for (int x = 0; x < ts; x++) {
				int argb = src.getRGB(x, y);
				int a = argb >>> 24;
				if (a == 0) {
					continue;
				}
				double f = 1.0;
				if ((mask & 1) != 0) {
					f = Math.min(f, y / (double) fade); // N
				}
				if ((mask & 2) != 0) {
					f = Math.min(f, (ts - 1 - x) / (double) fade); // E
				}
				if ((mask & 4) != 0) {
					f = Math.min(f, (ts - 1 - y) / (double) fade); // S
				}
				if ((mask & 8) != 0) {
					f = Math.min(f, x / (double) fade); // W
				}
				if (f > 1) {
					f = 1;
				}
				int na = (int) (a * f);
				out.setRGB(x, y, (na << 24) | (argb & 0xFFFFFF));
			}
		}
		return out;
	}

	/** Opaque blue with lighter horizontal ripple lines. */
	private static BufferedImage makeWater(int ts, Random rng) {
		BufferedImage img = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		g.setColor(new Color(38, 88, 172, 240));
		g.fillRect(0, 0, ts, ts);
		g.setStroke(new BasicStroke(2));
		for (int i = 0; i < 5; i++) {
			int y = rng.nextInt(ts);
			g.setColor(new Color(120, 180, 235, 90 + rng.nextInt(80)));
			int x = 0;
			int py = y;
			while (x < ts) {
				int nx = x + ts / 4;
				int ny = y + rng.nextInt(7) - 3;
				g.drawLine(x, py, Math.min(nx, ts), ny);
				x = nx;
				py = ny;
			}
		}
		g.dispose();
		return img;
	}

	/** Opaque brown with scattered darker/lighter speckle. */
	private static BufferedImage makeMud(int ts, Random rng) {
		BufferedImage img = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		g.setColor(new Color(96, 70, 44, 240));
		g.fillRect(0, 0, ts, ts);
		for (int i = 0; i < 40; i++) {
			int x = rng.nextInt(ts), y = rng.nextInt(ts), r = 1 + rng.nextInt(3);
			int d = rng.nextInt(50) - 25;
			g.setColor(new Color(clamp(96 + d), clamp(70 + d), clamp(44 + d), 200));
			g.fillOval(x, y, r, r);
		}
		g.dispose();
		return img;
	}

	/** Opaque dark green with dense tall blades. */
	private static BufferedImage makeCover(int ts, Random rng) {
		BufferedImage img = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		g.setColor(new Color(24, 74, 34, 240));
		g.fillRect(0, 0, ts, ts);
		for (int i = 0; i < 44; i++) {
			int bx = rng.nextInt(ts);
			int by = ts - rng.nextInt(ts / 3 + 1);
			int len = ts / 3 + rng.nextInt(ts / 2);
			int dx = rng.nextInt(5) - 2;
			g.setColor(new Color(30 + rng.nextInt(30), 120 + rng.nextInt(80), 40 + rng.nextInt(40)));
			g.setStroke(new BasicStroke(1 + rng.nextInt(2)));
			g.drawLine(bx, by, bx + dx, by - len);
		}
		g.dispose();
		return img;
	}

	private static int clamp(int v) {
		return v < 0 ? 0 : (v > 255 ? 255 : v);
	}
}
