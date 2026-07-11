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
 * top-down grass stipple, water ripples, mud speckle, tall-grass cover -- so
 * terrains are told apart by texture (identity), and a scalar value like
 * vegetation density rides on top by choosing a denser variant (magnitude).
 *
 * <p>Generated once at first use from a dedicated {@link Random} so it never
 * draws from the simulation RNG (determinism is untouched). Grass is
 * transparent-backed so the floor sprite shows between blades; water/mud/cover
 * are opaque tiles that stand in for the floor.
 */
public final class GroundTextures {

	private static final int LEVELS = 3; // vegetation density buckets
	private static final int VARIANTS = 3; // per-type variety to avoid tiling
	private static boolean ready = false;

	private static BufferedImage[][] grass; // [density][variant]
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
		grass = new BufferedImage[LEVELS][VARIANTS];
		for (int l = 0; l < LEVELS; l++) {
			for (int v = 0; v < VARIANTS; v++) {
				grass[l][v] = makeGrass(ts, 5 + l * 13, rng);
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

	/**
	 * The texture for a tile, or null for bare ground (let the floor show). The
	 * {@code hash} is a stable per-tile value so a tile always draws the same
	 * variant.
	 */
	public static BufferedImage forTile(Tile t, long now, int hash) {
		ensure();
		int v = (hash & 0x7fffffff) % VARIANTS;
		switch (t.getType()) {
		case TYPE_WATER:
			return water[v];
		case TYPE_MUD:
			return mud[v];
		case TYPE_COVER:
			return cover[v];
		case TYPE_FLOOR:
			double veg = t.getVegetation(now) / Tile.VEG_MAX;
			if (veg < 0.12) {
				return null; // grazed to bare earth
			}
			int level = (int) Math.min(LEVELS - 1, veg * LEVELS);
			return grass[level][v];
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

	/**
	 * Top-down stipple: grass as scattered dots (blade tips seen from above)
	 * over a faint earth-green tint. More dots = lusher. Minimalist and abstract,
	 * so it reads as ground cover from above rather than a side-on lawn.
	 */
	private static BufferedImage makeGrass(int ts, int count, Random rng) {
		BufferedImage img = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		// A near-solid grass-green ground so the tile reads as grass (not the
		// blue floor showing through), with the stipple as top-down grain.
		g.setColor(new Color(46, 104, 54, 205));
		g.fillRect(0, 0, ts, ts);
		for (int i = 0; i < count; i++) {
			int x = rng.nextInt(ts);
			int y = rng.nextInt(ts);
			int r = 2 + rng.nextInt(2);
			// Dots a touch lighter/darker than the ground for a subtle stipple.
			boolean light = rng.nextBoolean();
			g.setColor(light ? new Color(90, 175, 95, 200) : new Color(28, 74, 38, 200));
			g.fillOval(x, y, r, r);
		}
		g.dispose();
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
