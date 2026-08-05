package net.hedinger.prototype.engine;

import java.awt.AlphaComposite;
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
	/** Opaque deep-water surface the caller fills before the overlay. */
	public static final Color WATER_BLUE = new Color(30, 78, 150);
	/** Turquoise shallows drawn where water meets land. */
	public static final Color SHORE = new Color(96, 190, 205);

	// ---- pixel-ground palette (RenderFx.pixelGround) -----------------------
	// Per terrain class: {shadow, base, highlight}, natural palette from the
	// art-style prototype. Colour = ramp indexed by a world-space shade noise.
	public static final int CLS_WATER = 0, CLS_GRASS = 1, CLS_SOIL = 2, CLS_MUD = 3, CLS_COVER = 4,
			CLS_WALL = 5, CLS_HOLE = 6;
	private static final int[][] RAMP = {
			{ 0x1a3a60, 0x24568c, 0x3172b0 }, // water
			{ 0x2a4d24, 0x3f7a38, 0x5f9850 }, // grass
			{ 0x40301f, 0x63472e, 0x866543 }, // soil / bare
			{ 0x38291a, 0x574024, 0x775a38 }, // mud
			{ 0x1b3a16, 0x2b5422, 0x456c36 }, // cover (dark grass)
			{ 0x3a3e49, 0x565b69, 0x7c828f }, // wall (stone)
			{ 0x090a0e, 0x14161f, 0x222634 }, // hole (pit)
	};

	/** Whether a class is a solid structure (wall) rather than open ground. */
	public static boolean isStructure(int cls) {
		return cls == CLS_WALL;
	}

	/** Terrain colour class for a tile: ground, structure, or -1 (ramp). */
	public static int groundClass(Tile t, long now) {
		switch (t.getType()) {
		case TYPE_WATER:
			return CLS_WATER;
		case TYPE_MUD:
			return CLS_MUD;
		case TYPE_COVER:
			return CLS_COVER;
		case TYPE_WALL:
			return CLS_WALL;
		case TYPE_HOLE:
			return CLS_HOLE;
		case TYPE_FLOOR:
			return t.getVegetation(now) / Tile.VEG_MAX < 0.28 ? CLS_SOIL : CLS_GRASS;
		default:
			return -1;
		}
	}

	/** Colour for a ground class at a world position. Keeps the full three-shade
	 *  contrast (crisp, pixelated) but samples a finer-grained noise, so shadow and
	 *  highlight scatter as small speckles/clumps -- a textured surface rather than
	 *  the tile-sized blobs that read as camouflage. */
	public static int groundColor(int cls, double wx, double wy) {
		double sh = Utils.noise2(wx, wy, 3.7);
		return RAMP[cls][sh < 0.32 ? 0 : (sh > 0.80 ? 2 : 1)];
	}

	// ---- ordered dither, striation, cracks --------------------------------

	/** 4x4 Bayer matrix: the classic ordered-dither threshold pattern. */
	private static final int[] BAYER4 = {
			0, 8, 2, 10,
			12, 4, 14, 6,
			3, 11, 1, 9,
			15, 7, 13, 5 };

	/** Ordered-dither threshold in (0,1) for a world-absolute art-pixel. */
	public static double bayer(int px, int py) {
		return (BAYER4[(py & 3) * 4 + (px & 3)] + 0.5) / 16.0;
	}

	/**
	 * A ramp shade for a continuous shade index {@code p} in [0,2], picked by
	 * Bayer-dithering between the two adjacent shades: the fractional part of
	 * {@code p} becomes checkerboard coverage of the brighter shade. Gradients
	 * thus render as ordered-dither mixes of the same three ramp colours, never
	 * as new in-between colours.
	 */
	public static int ditherRamp(int cls, double p, int px, int py) {
		p = p < 0 ? 0 : (p > 2 ? 2 : p);
		int lo = (int) p;
		if (lo >= 2) {
			return RAMP[cls][2];
		}
		return RAMP[cls][p - lo > bayer(px, py) ? lo + 1 : lo];
	}

	/**
	 * Dithered ground colour: the same shade noise and ramp as
	 * {@link #groundColor}, but the transition zone between adjacent shades is
	 * rendered as a narrow Bayer-dithered border around otherwise solid shade
	 * clusters -- pixel-art cluster shading, not a full-field checkerboard. The
	 * sharpening keeps ~2/3 of every shade band solid and dithers the rest.
	 */
	public static int groundColorDithered(int cls, double wx, double wy, int px, int py) {
		double sh = Utils.noise2(wx, wy, 3.7);
		double p = (sh - 0.28) / 0.56 * 2;
		p = p < 0 ? 0 : (p > 2 ? 2 : p);
		int lo = (int) p;
		double frac = p - lo;
		// Sharpen: only the middle third of the transition dithers.
		frac = frac < 0.33 ? 0 : (frac > 0.66 ? 1 : (frac - 0.33) / 0.33);
		return ditherRamp(cls, lo + frac, px, py);
	}

	/**
	 * Water as pixel-art strokes: short flat horizontal dashes (hard breaks,
	 * one shade per dash) over the base blue, with a coarse world-space swell
	 * noise biasing whole regions darker or lighter so the dashes cluster into
	 * bands instead of scattering uniformly. Purely positional and discrete --
	 * no stretched gradients.
	 */
	public static int waterDash(double wx, double wy, int px, int py) {
		int len = 5 + (int) (hash01(py, 0, 7) * 3); // dash length per row, 5..7 px
		int phase = (int) (hash01(py, 1, 8) * len); // stagger rows against each other
		int seg = Math.floorDiv(px + phase, len);
		double r = hash01(seg, py, 9);
		double swell = Utils.noise2(wx * 0.6, wy * 1.3, 1.6); // coarse band bias
		double dark = 0.10 + 0.28 * swell; // dark-dash share, ~0.1 .. 0.38
		return RAMP[CLS_WATER][r < dark ? 0 : (r > 0.94 ? 2 : 1)];
	}

	/**
	 * Wall rock as pixel-art striation: each art-pixel column carries flat
	 * vertical dashes of 4-7 px with hard, per-column-staggered breaks -- the
	 * hand-placed look of a carved face, not stretched noise. Adjacent dashes
	 * that hash to the same shade merge into longer runs on their own. The lit
	 * top edge shifts dashes one shade up, the base one shade down.
	 */
	public static int wallStria(int px, int py, boolean litTop, boolean darkBase) {
		int len = 4 + (int) (hash01(px, 0, 10) * 4); // dash length per column, 4..7 px
		int phase = (int) (hash01(px, 1, 11) * len); // stagger columns
		int seg = Math.floorDiv(py + phase, len);
		double r = hash01(px, seg, 12);
		int idx = r < 0.30 ? 0 : (r > 0.72 ? 2 : 1);
		if (litTop) {
			idx = Math.min(2, idx + 1);
		} else if (darkBase) {
			idx = Math.max(0, idx - 1);
		}
		return RAMP[CLS_WALL][idx];
	}

	/**
	 * True where (wx,wy) lies on the crack network that plates bare ground: the
	 * ridge lines of a jittered-lattice Voronoi diagram (points where the two
	 * nearest feature points are nearly equidistant). Purely positional, so the
	 * network is seamless across tiles and chunk bakes.
	 */
	public static boolean crack(double wx, double wy) {
		double cell = 1.15; // plate diameter in tiles
		double cx = wx / cell, cy = wy / cell;
		int ix = (int) Math.floor(cx), iy = (int) Math.floor(cy);
		double d1 = 9, d2 = 9;
		for (int oy = -1; oy <= 1; oy++) {
			for (int ox = -1; ox <= 1; ox++) {
				int nx = ix + ox, ny = iy + oy;
				double dx = cx - (nx + 0.18 + 0.64 * hash01(nx, ny, 1));
				double dy = cy - (ny + 0.18 + 0.64 * hash01(nx, ny, 2));
				double d = Math.sqrt(dx * dx + dy * dy);
				if (d < d1) {
					d2 = d1;
					d1 = d;
				} else if (d < d2) {
					d2 = d;
				}
			}
		}
		return d2 - d1 < 0.10;
	}

	/** Deterministic integer-lattice hash to [0,1). */
	private static double hash01(int x, int y, int s) {
		int h = x * 374761393 + y * 668265263 + s * (int) 2246822519L;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0x7fffffff) / (double) 0x7fffffff;
	}

	/** A specific ramp shade (0 shadow, 1 base, 2 highlight) for a class. */
	public static int rampColor(int cls, int idx) {
		return RAMP[cls][idx];
	}

	private static final int VARIANTS = 3;
	private static final int FIELD_TILES = 4; // mottle field spans this many tiles before repeating
	private static boolean ready = false;

	private static BufferedImage[][] stipple;   // [thin level 0..1][variant]
	private static BufferedImage[] mottleField; // [lush level 0..1] big seamless world-space field
	private static BufferedImage[] edgeMask;    // [16] per-tile alpha ramp to fade edges
	private static BufferedImage mottleTmp;     // reused scratch for edge-faded mottle tiles
	private static BufferedImage featherTmp;    // reused scratch for edge-feathered opaque fills
	private static BufferedImage waterField;    // big seamless world-space ripple field
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
				stipple[l][v] = makeStipple(ts, l == 0 ? 55 : 105, rng);
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
		featherTmp = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
		waterField = makeWaterField(big, ts, rng);

		mud = new BufferedImage[VARIANTS];
		cover = new BufferedImage[VARIANTS];
		for (int v = 0; v < VARIANTS; v++) {
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

	/**
	 * Fills a tile with an opaque colour or texture, but fades ({@code edgeMask}
	 * bits N=1, E=2, S=4, W=8) the edges that face a different terrain, so the
	 * fill melts into whatever substrate is already drawn underneath rather than
	 * ending in a hard straight tile seam. Interior tiles (mask 0) draw the plain
	 * opaque fill unchanged. Pass a colour or an image; the other is null.
	 */
	public static void drawFeathered(Graphics2D g, int sx, int sy, int ts,
			Color color, BufferedImage img, int edgeMaskBits) {
		ensure();
		if ((edgeMaskBits & 15) == 0) {
			if (img != null) {
				g.drawImage(img, sx, sy, ts, ts, null);
			} else {
				g.setColor(color);
				g.fillRect(sx, sy, ts, ts);
			}
			return;
		}
		Graphics2D tg = featherTmp.createGraphics();
		tg.setComposite(AlphaComposite.Src); // overwrite the scratch fully
		if (img != null) {
			tg.drawImage(img, 0, 0, ts, ts, null);
		} else {
			tg.setColor(color);
			tg.fillRect(0, 0, ts, ts);
		}
		tg.setComposite(AlphaComposite.DstIn); // keep dst alpha * ramp
		tg.drawImage(edgeMask[edgeMaskBits & 15], 0, 0, null);
		tg.dispose();
		g.drawImage(featherTmp, sx, sy, null);
	}

	/** Opaque per-tile texture for mud/cover, else null. */
	public static BufferedImage terrain(Tile t, int hash) {
		ensure();
		int v = (hash & 0x7fffffff) % VARIANTS;
		switch (t.getType()) {
		case TYPE_MUD:
			return mud[v];
		case TYPE_COVER:
			return cover[v];
		default:
			return null;
		}
	}

	/**
	 * Draws the water ripple overlay for one tile, sampled from the continuous
	 * world-space field at (worldX, worldY) so ripples flow across water tiles.
	 * The caller fills {@link #WATER_BLUE} first.
	 */
	public static void drawWater(Graphics2D g, int sx, int sy, int ts, int worldX, int worldY) {
		ensure();
		int big = FIELD_TILES * ts;
		int srcX = Math.floorMod(worldX * ts, big);
		int srcY = Math.floorMod(worldY * ts, big);
		g.drawImage(waterField, sx, sy, sx + ts, sy + ts, srcX, srcY, srcX + ts, srcY + ts, null);
	}

	// ---- generators --------------------------------------------------------

	private static Graphics2D gfx(BufferedImage img) {
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return g;
	}

	/**
	 * Dots as top-down grass grain, transparent-backed. Dense and fairly high
	 * contrast (bright tips + dark shade) so thin grass clearly reads as a
	 * stippled texture rather than flat green -- it has to stay legible under the
	 * global scanline overlay the view composites on top.
	 */
	private static BufferedImage makeStipple(int ts, int count, Random rng) {
		BufferedImage img = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		for (int i = 0; i < count; i++) {
			int x = rng.nextInt(ts), y = rng.nextInt(ts), r = 2 + rng.nextInt(3);
			boolean light = rng.nextBoolean();
			g.setColor(light ? new Color(116, 200, 112, 230) : new Color(18, 56, 26, 230));
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

	/**
	 * One large seamless top-down water surface, transparent-backed (drawn over
	 * the {@link #WATER_BLUE} base). Not ripple lines (which read side-on) but a
	 * satellite look: subtle low-contrast reflectance/depth mottle plus a sparse
	 * scatter of bright sun-glint specks. Blobs are drawn at the nine period
	 * offsets so the field wraps; tiles sample contiguous windows, so it flows
	 * continuously across water tiles.
	 */
	private static BufferedImage makeWaterField(int big, int ts, Random rng) {
		BufferedImage img = new BufferedImage(big + ts, big + ts, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		int blobs = 14 * FIELD_TILES * FIELD_TILES;
		for (int i = 0; i < blobs; i++) {
			int x = rng.nextInt(big), y = rng.nextInt(big);
			int r = ts / 4 + rng.nextInt(ts / 2);
			boolean light = rng.nextBoolean();
			g.setColor(light ? new Color(120, 175, 230, 30) : new Color(16, 50, 118, 48));
			for (int ox = -big; ox <= big; ox += big) {
				for (int oy = -big; oy <= big; oy += big) {
					g.fillOval(x - r / 2 + ox, y - r / 2 + oy, r, r);
				}
			}
		}
		int glints = 3 * FIELD_TILES * FIELD_TILES; // specular sun sparkle
		for (int i = 0; i < glints; i++) {
			int x = rng.nextInt(big), y = rng.nextInt(big);
			int r = 2 + rng.nextInt(3);
			g.setColor(new Color(205, 232, 255, 90 + rng.nextInt(90)));
			for (int ox = -big; ox <= big; ox += big) {
				for (int oy = -big; oy <= big; oy += big) {
					g.fillOval(x - r / 2 + ox, y - r / 2 + oy, r, r);
				}
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

	/**
	 * Dense dark canopy, top-down: a shadowy green base with many overlapping
	 * clumps so it reads as a thicket you can hide in (it blocks sight), distinct
	 * from open grass by being darker and busier -- no side-view blades.
	 */
	private static BufferedImage makeCover(int ts, Random rng) {
		BufferedImage img = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		g.setColor(new Color(26, 72, 34, 245));
		g.fillRect(0, 0, ts, ts);
		// Overlapping blobs -> a bushy canopy seen from above.
		for (int i = 0; i < 64; i++) {
			int x = rng.nextInt(ts), y = rng.nextInt(ts);
			int r = ts / 8 + rng.nextInt(ts / 4);
			boolean light = rng.nextInt(3) == 0;
			g.setColor(light ? new Color(66, 146, 72, 110) : new Color(15, 52, 24, 140));
			g.fillOval(x - r / 2, y - r / 2, r, r);
		}
		// A few bright tips for sparkle, still round (no strokes).
		for (int i = 0; i < 10; i++) {
			int x = rng.nextInt(ts), y = rng.nextInt(ts), r = 1 + rng.nextInt(2);
			g.setColor(new Color(120, 200, 120, 150));
			g.fillOval(x, y, r, r);
		}
		g.dispose();
		return img;
	}

	private static int clamp(int v) {
		return v < 0 ? 0 : (v > 255 ? 255 : v);
	}
}
