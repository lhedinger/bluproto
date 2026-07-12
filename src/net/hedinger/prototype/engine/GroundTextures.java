package net.hedinger.prototype.engine;

import java.awt.AlphaComposite;
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
 * <p>Grass is a flat green {@link #GRASS_GREEN} base (drawn by the caller) plus
 * a pattern overlay. Lush mottle is sampled from one large, toroidally seamless
 * <em>world-space</em> field: each tile draws the window of that field at its
 * world position, so blobs flow continuously across tile boundaries and mottle
 * neighbours connect. Where a mottle tile borders thinner grass its overlay is
 * faded on that edge (an alpha ramp composited in), so lush clumps melt into the
 * plain green the stipple shares instead of ending in a hard square. All of it
 * is baked once from a dedicated {@link Random} that never touches the sim RNG.
 */
public final class GroundTextures {

	/** Opaque earth under everything, so bare/grazed ground reads as soil. */
	public static final Color SOIL = new Color(78, 64, 46);
	/** Opaque grass ground the caller fills before the pattern overlay; it hides
	 * the blue floor sprite so grass reads green, not murky teal. */
	public static final Color GRASS_GREEN = new Color(58, 120, 60);

	private static final int VARIANTS = 3;
	private static final int FIELD_TILES = 4; // mottle field spans this many tiles before repeating
	private static boolean ready = false;

	private static BufferedImage[][] stipple;   // [thin level 0..1][variant]
	private static BufferedImage[] mottleField; // [lush level 0..1] big seamless world-space field
	private static BufferedImage[] edgeMask;    // [16] per-tile alpha ramp to fade edges
	private static BufferedImage mottleTmp;     // reused scratch for edge-faded mottle tiles
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
		int big = FIELD_TILES * ts;
		Random rng = new Random(0x6C9A11E5L); // dedicated: not the sim RNG

		stipple = new BufferedImage[2][VARIANTS];
		for (int l = 0; l < 2; l++) {
			for (int v = 0; v < VARIANTS; v++) {
				stipple[l][v] = makeStipple(ts, l == 0 ? 22 : 42, rng);
			}
		}
		mottleField = new BufferedImage[2];
		mottleField[0] = makeMottleField(big, ts, 12 * FIELD_TILES * FIELD_TILES, rng);
		mottleField[1] = makeMottleField(big, ts, 20 * FIELD_TILES * FIELD_TILES, rng);
		edgeMask = new BufferedImage[16];
		for (int m = 0; m < 16; m++) {
			edgeMask[m] = makeEdgeMask(ts, m);
		}
		mottleTmp = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);

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

	/** The transparent stipple overlay (thin grass) for a level/variant. */
	public static BufferedImage stipplePattern(int level, int variant) {
		ensure();
		int v = (variant & 0x7fffffff) % VARIANTS;
		return stipple[Math.min(1, level)][v];
	}

	/**
	 * Draws the lush mottle overlay for one tile, sampled from the continuous
	 * world-space field at (worldX, worldY) so it joins its mottle neighbours.
	 * {@code edgeMask} (bits N=1, E=2, S=4, W=8) fades the overlay on edges that
	 * face thinner grass.
	 */
	public static void drawMottle(Graphics2D g, int sx, int sy, int ts, int level,
			int worldX, int worldY, int edgeMaskBits) {
		ensure();
		BufferedImage field = mottleField[level - 2];
		int big = FIELD_TILES * ts;
		int srcX = Math.floorMod(worldX * ts, big);
		int srcY = Math.floorMod(worldY * ts, big);
		if ((edgeMaskBits & 15) == 0) {
			g.drawImage(field, sx, sy, sx + ts, sy + ts, srcX, srcY, srcX + ts, srcY + ts, null);
			return;
		}
		Graphics2D tg = mottleTmp.createGraphics();
		tg.setComposite(AlphaComposite.Src); // overwrite the scratch with this window
		tg.drawImage(field, 0, 0, ts, ts, srcX, srcY, srcX + ts, srcY + ts, null);
		tg.setComposite(AlphaComposite.DstIn); // keep dst alpha * ramp
		tg.drawImage(edgeMask[edgeMaskBits & 15], 0, 0, null);
		tg.dispose();
		g.drawImage(mottleTmp, sx, sy, null);
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
	 * One large toroidally seamless field of organic light/dark blotches. Blobs
	 * are placed over [0, big) and drawn at the nine period offsets so the field
	 * wraps; the image is padded by one tile so any tile-window inside it is
	 * fully readable. Tiles sample contiguous windows, so blobs cross boundaries.
	 */
	private static BufferedImage makeMottleField(int big, int ts, int blobs, Random rng) {
		BufferedImage img = new BufferedImage(big + ts, big + ts, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		for (int i = 0; i < blobs; i++) {
			int x = rng.nextInt(big), y = rng.nextInt(big);
			int r = ts / 6 + rng.nextInt(ts / 3);
			boolean light = rng.nextBoolean();
			g.setColor(light ? new Color(84, 168, 92, 80) : new Color(24, 72, 34, 95));
			for (int ox = -big; ox <= big; ox += big) {
				for (int oy = -big; oy <= big; oy += big) {
					g.fillOval(x - r / 2 + ox, y - r / 2 + oy, r, r);
				}
			}
		}
		g.dispose();
		return img;
	}

	/** White mask whose alpha ramps to 0 on each flagged edge (N=1,E=2,S=4,W=8). */
	private static BufferedImage makeEdgeMask(int ts, int mask) {
		BufferedImage img = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
		int fade = ts / 4;
		for (int y = 0; y < ts; y++) {
			for (int x = 0; x < ts; x++) {
				double f = 1.0;
				if ((mask & 1) != 0) {
					f = Math.min(f, y / (double) fade);
				}
				if ((mask & 2) != 0) {
					f = Math.min(f, (ts - 1 - x) / (double) fade);
				}
				if ((mask & 4) != 0) {
					f = Math.min(f, (ts - 1 - y) / (double) fade);
				}
				if ((mask & 8) != 0) {
					f = Math.min(f, x / (double) fade);
				}
				if (f > 1) {
					f = 1;
				}
				int a = (int) (255 * f);
				img.setRGB(x, y, (a << 24) | 0xFFFFFF);
			}
		}
		return img;
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
