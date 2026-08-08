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
			CLS_WALL = 5, CLS_HOLE = 6, CLS_STONE = 7, CLS_FUNGUS = 8, CLS_RUBBLE = 9,
			CLS_SAND = 10, CLS_REEDS = 11, CLS_SHALLOWS = 12, CLS_QUICKSAND = 13,
			CLS_CRYSTAL = 14, CLS_VENT = 15, CLS_WALL_BUILT = 16, CLS_PAVED = 17;
	private static final int[][] RAMP = {
			{ 0x1a3a60, 0x24568c, 0x3172b0 }, // water
			{ 0x2a4d24, 0x3f7a38, 0x5f9850 }, // grass
			{ 0x40301f, 0x63472e, 0x866543 }, // soil / bare
			{ 0x38291a, 0x574024, 0x775a38 }, // mud
			{ 0x1b3a16, 0x2b5422, 0x456c36 }, // cover (dark grass)
			{ 0x3a3e49, 0x565b69, 0x7c828f }, // wall (stone)
			{ 0x090a0e, 0x14161f, 0x222634 }, // hole (pit)
			{ 0x2e323c, 0x484d59, 0x666c7a }, // stone floor (darker than wall mass)
			{ 0x16352a, 0x2a6b4f, 0x54c78e }, // fungus (bioluminescent teal-green)
			{ 0x333845, 0x515866, 0x747b8a }, // rubble (between stone floor and wall)
			{ 0x6e5f42, 0x98865c, 0xc0aa7e }, // sand (pale warm)
			{ 0x14301f, 0x2c5a36, 0x4f8752 }, // reeds (wet green)
			{ 0x2b6a78, 0x4093a6, 0x63becd }, // shallows (the old SHORE turquoise family)
			{ 0x483d2a, 0x685740, 0x8c795a }, // quicksand (wet, darker sand)
			{ 0x232c44, 0x46568a, 0x7d96c8 }, // crystal (cool mineral blue)
			{ 0x261f1c, 0x3c332c, 0x584c40 }, // vent (scorched stone)
			{ 0x453f33, 0x665e4c, 0x8a8069 }, // built wall (dressed warm masonry)
			{ 0x36342e, 0x504d44, 0x6d695d }, // paved floor (worn warm slabs)
	};
	/** The rare over-bright spore speck on a fungus clump -- the one colour
	 *  allowed to sit above its ramp, so the beds read as faintly emissive. */
	private static final int FUNGUS_SPARK = 0x9df5c6;
	/** Wildflower / berry accents, matching the shrub decoration palette
	 *  (Grid.SH_BERRY / SH_FLOWER) so meadow blossoms, shrub berries and
	 *  thicket berries read as one flora. */
	private static final int BLOOM_RED = 0xE0455F, BLOOM_CREAM = 0xF0E8C6;
	/** Crystal facet glint and vent ember: the mineral accents, disciplined
	 *  the same way as the fungus spark -- rare, small, deliberate. */
	private static final int CRYSTAL_SPARK = 0xD0ECFF, VENT_EMBER = 0xD8622C;

	/** Whether a class is a solid structure rather than open ground. */
	public static boolean isStructure(int cls) {
		return cls == CLS_WALL || cls == CLS_WALL_BUILT || cls == CLS_CRYSTAL;
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
		case TYPE_STONE:
			return CLS_STONE;
		case TYPE_FUNGUS:
			return CLS_FUNGUS;
		case TYPE_RUBBLE:
			return CLS_RUBBLE;
		case TYPE_SAND:
			return CLS_SAND;
		case TYPE_REEDS:
			return CLS_REEDS;
		case TYPE_SHALLOWS:
			return CLS_SHALLOWS;
		case TYPE_QUICKSAND:
			return CLS_QUICKSAND;
		case TYPE_CRYSTAL:
			return CLS_CRYSTAL;
		case TYPE_VENT:
			return CLS_VENT;
		case TYPE_WALL_BUILT:
			return CLS_WALL_BUILT;
		case TYPE_PAVED:
			return CLS_PAVED;
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
	 * Calm mineral ground: base-dominant, structured by coarse dithered
	 * shadow patches (solid cores, dithered borders) and sparse 2-px grains
	 * -- never lone pixels. The quiet interior shared by soil, mud and stone,
	 * over which the crack networks draw their seams.
	 */
	public static int quietGround(int cls, double wx, double wy, int px, int py) {
		double g = hash01(px >> 1, py, 46); // 2-px grain clusters
		if (g < 0.05) {
			return RAMP[cls][0];
		}
		if (g > 0.965) {
			return RAMP[cls][2];
		}
		double sh = Utils.noise2(wx + 13, wy + 29, 0.8);
		double p = (sh - 0.30) / 0.42;
		p = p < 0 ? 0 : (p > 1 ? 1 : p);
		p = p < 0.33 ? 0 : (p > 0.66 ? 1 : (p - 0.33) / 0.33); // sharpen
		return ditherRamp(cls, p, px, py);
	}

	/**
	 * Grass as hand-drawn lawns are textured: a calm base broken into coarse
	 * light/shadow patches, scattered with stamped plant motifs. Most cells
	 * grow a simple tuft (2-px lit tip over a shadow root); some grow a wide
	 * three-tip clump; lush ground occasionally sprouts a darker broadleaf
	 * rosette (borrowing the thicket ramp, so it reads as a different plant)
	 * or a tiny wildflower in the shrub-accent colours. Motif density follows
	 * the live vegetation, so a grazed lawn goes quiet before it goes bare,
	 * and the grass highlight shade appears only on blade tips.
	 */
	public static int grass(double wx, double wy, int px, int py, double veg) {
		int C = 3; // motif lattice pitch, art-px
		int cx0 = Math.floorDiv(px, C), cy0 = Math.floorDiv(py, C);
		for (int oy = -1; oy <= 1; oy++) {
			for (int ox = -1; ox <= 1; ox++) {
				int cx = cx0 + ox, cy = cy0 + oy;
				if (hash01(cx, cy, 40) > 0.10 + 0.38 * veg) {
					continue; // nothing grows in this cell
				}
				int tx = cx * C + (int) (hash01(cx, cy, 41) * C);
				int ty = cy * C + (int) (hash01(cx, cy, 42) * C);
				int dx = px - tx, dy = py - ty;
				double kind = hash01(cx, cy, 48);
				if (kind < 0.05 && veg > 0.5) {
					// Wildflower: a 2-px blossom over a leaf shadow.
					if (dy == 0 && (dx == 0 || dx == 1)) {
						return hash01(cx, cy, 49) < 0.7 ? BLOOM_RED : BLOOM_CREAM;
					}
					if (dx == 0 && dy == 1) {
						return RAMP[CLS_GRASS][0];
					}
				} else if (kind < 0.11 && veg > 0.55) {
					// Broadleaf rosette: a darker plus-shaped plant.
					if (dx == 0 && dy == 0) {
						return RAMP[CLS_COVER][2];
					}
					if (Math.abs(dx) + Math.abs(dy) == 1) {
						return RAMP[CLS_COVER][1];
					}
				} else if (kind < 0.30) {
					// Wide clump: three fanned lit tips over a shadow root.
					if ((dy == 0 && Math.abs(dx) == 1) || (dx == 0 && dy == -1)) {
						return RAMP[CLS_GRASS][2];
					}
					if (dx == 0 && dy == 0) {
						return RAMP[CLS_GRASS][1];
					}
					if (dx == 0 && dy == 1) {
						return RAMP[CLS_GRASS][0];
					}
				} else {
					// Simple tuft: 2-px lit tip over a shadow root.
					if (dx == 0 && (dy == 0 || dy == -1)) {
						return RAMP[CLS_GRASS][2];
					}
					if (dx == 0 && dy == 1) {
						return RAMP[CLS_GRASS][0];
					}
				}
			}
		}
		double sh = Utils.noise2(wx, wy, 0.8);
		double p = (sh - 0.30) / 0.42;
		p = p < 0 ? 0 : (p > 1 ? 1 : p);
		p = p < 0.33 ? 0 : (p > 0.66 ? 1 : (p - 0.33) / 0.33); // sharpen
		return ditherRamp(CLS_GRASS, p, px, py); // calm shadow/base patches
	}

	/**
	 * Thicket canopy: overlapping leaf-clump caps stamped on a jittered
	 * lattice -- the round-clump grammar of top-down foliage. Each cap
	 * self-shades (some crowns lit, lower rims dark) and the crevices between
	 * caps drop to shadow, so the mass reads bumpy rather than flat.
	 *
	 * <p>Variety comes from two coarse world-space fields rather than
	 * per-cell noise, so whole stands differ in character: a growth field
	 * swings cap size between old-growth mounds and fine young scrub, and a
	 * light field swings the share of lit crowns between airy bright stands
	 * and dense dark ones. On top of that, the odd cell is an open gap, big
	 * caps carry 2-px leaf flecks so they do not go flat, and the occasional
	 * clump fruits (red berries) or flowers (cream blossoms) at its core --
	 * the same accents the shrubs wear.
	 */
	public static int canopy(double wx, double wy, int px, int py) {
		int C = 4; // cap lattice pitch, art-px
		int cx0 = Math.floorDiv(px, C), cy0 = Math.floorDiv(py, C);
		double growth = Utils.noise2(wx + 41, wy + 83, 0.35); // stand character
		double light = Utils.noise2(wx + 19, wy + 67, 0.3);
		double bestD = 1e9, bestDy = 0, bestR = 1;
		boolean bestLit = false;
		int bestCx = 0, bestCy = 0;
		for (int oy = -1; oy <= 1; oy++) {
			for (int ox = -1; ox <= 1; ox++) {
				int cx = cx0 + ox, cy = cy0 + oy;
				if (hash01(cx, cy, 53) < 0.06) {
					continue; // an open gap in the canopy
				}
				double jx = cx * C + 1 + hash01(cx, cy, 43) * (C - 2);
				double jy = cy * C + 1 + hash01(cx, cy, 44) * (C - 2);
				double r = 1.6 + 1.6 * growth + hash01(cx, cy, 45) * 1.2;
				double dx = px + 0.5 - jx, dy = py + 0.5 - jy;
				double d = Math.sqrt(dx * dx + dy * dy);
				if (d - r < bestD - bestR) {
					bestD = d;
					bestDy = dy;
					bestR = r;
					bestLit = hash01(cx, cy, 47) < 0.25 + 0.6 * light;
					bestCx = cx;
					bestCy = cy;
				}
			}
		}
		if (bestD < bestR) {
			// The odd clump fruits or flowers at its core.
			double accent = hash01(bestCx, bestCy, 52);
			if (bestD < 0.7 && accent > 0.86) {
				return accent > 0.95 ? BLOOM_CREAM : BLOOM_RED;
			}
			if (bestLit && bestDy < -0.3 * bestR) {
				return RAMP[CLS_COVER][2]; // lit crown of this clump
			}
			// Leaf flecks keep a broad old-growth cap from reading flat.
			if (bestR > 3 && bestD < bestR * 0.8 && hash01(px >> 1, py, 54) < 0.08) {
				return RAMP[CLS_COVER][0];
			}
			return RAMP[CLS_COVER][bestDy > 0.55 * bestR ? 0 : 1]; // flank / dark lower rim
		}
		return RAMP[CLS_COVER][0]; // crevice between clumps
	}

	/**
	 * Top-down water: a calm surface of broad shadow/base patches (coarse
	 * noise, sharpened so patch cores stay solid and only their borders
	 * dither) with rare 2-px sun glints near the shore. No directional
	 * strokes -- from straight above, water has no profile to band.
	 *
	 * <p>{@code depth} in [0,1] is the distance-from-shore term: it pulls the
	 * shade index down so a lake darkens toward its middle, and past the
	 * shadow shade it dither-fades into a still darker abyss tone, so a big
	 * lake reads as a deep hole and a puddle stays bright.
	 */
	public static int waterTop(double wx, double wy, int px, int py, double depth) {
		if (depth < 0.55 && hash01(px >> 1, py, 14) > 0.992) {
			return RAMP[CLS_WATER][2]; // sparse glint, 2 px wide, shallows only
		}
		double sh = Utils.noise2(wx + 31, wy + 17, 0.6);
		double p = (sh - 0.24) / 0.44; // continuous shadow..base index
		p = p < 0 ? 0 : (p > 1 ? 1 : p);
		p = p < 0.33 ? 0 : (p > 0.66 ? 1 : (p - 0.33) / 0.33); // sharpen
		p -= depth * 1.7; // deeper water -> darker shades
		if (p >= 0) {
			return ditherRamp(CLS_WATER, p, px, py);
		}
		double deep = Math.min(1, -p / 0.7); // past shadow: fade to the abyss tone
		return bayer(px, py) < deep ? darken(RAMP[CLS_WATER][0], 0.70) : RAMP[CLS_WATER][0];
	}

	/**
	 * The flat top of a wall mass -- the cross-section seen from above. Kept
	 * calm: base shade with sparse 2x2-px darker chips and the odd light
	 * fleck, so the mass reads as one quiet solid and the carved texture is
	 * saved for the vertical face ({@link #wallFace}).
	 */
	public static int wallTop(int px, int py, boolean litEdge) {
		double r = hash01(px >> 1, py >> 1, 13);
		int idx = r < 0.12 ? 0 : (r > 0.96 ? 2 : 1);
		if (litEdge) {
			idx = Math.min(2, idx + 1);
		}
		return RAMP[CLS_WALL][idx];
	}

	/**
	 * The exposed vertical face of a wall (where it fronts open ground to the
	 * south): flat vertical dashes of 4-7 px with hard, per-column-staggered
	 * breaks, biased a shade dark so the face reads as the shadowed side of a
	 * raised mass. Adjacent dashes that hash alike merge into longer runs on
	 * their own.
	 */
	public static int wallFace(int px, int py) {
		int len = 4 + (int) (hash01(px, 0, 10) * 4); // dash length per column, 4..7 px
		int phase = (int) (hash01(px, 1, 11) * len); // stagger columns
		int seg = Math.floorDiv(py + phase, len);
		double r = hash01(px, seg, 12);
		return RAMP[CLS_WALL][r < 0.45 ? 0 : (r > 0.90 ? 2 : 1)];
	}

	/**
	 * True where (wx,wy) lies on the crack network that plates bare ground: the
	 * ridge lines of a jittered-lattice Voronoi diagram (points where the two
	 * nearest feature points are nearly equidistant). {@code cell} is the plate
	 * diameter in tiles and {@code width} the seam width in tiles -- absolute,
	 * so small plates keep readable seams. Purely positional, so the network
	 * is seamless across tiles and chunk bakes.
	 */
	public static boolean crack(double wx, double wy, double cell, double width) {
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
		return d2 - d1 < width / cell;
	}

	/**
	 * Cave fungus beds drawn as pixel-art clusters, not noise: discrete
	 * rounded caps stamped on a jittered lattice, each cap shaded as a shape
	 * -- lit crown toward screen-north, base flank, and a grounded shadow rim
	 * under its south edge -- so a bed reads as a mass of individual growths
	 * (the benthic-node look). A coarse clump field gates where caps appear;
	 * a few caps carry a small glowing core, the bed's emissive accent.
	 * {@code veg} in [0,1] is the tile's live vegetation fraction: grazing
	 * thins the caps, shrinks them and kills the glow.
	 */
	public static int fungus(double wx, double wy, int px, int py, double veg) {
		int C = 4; // candidate-cap lattice pitch, art-px
		int cx0 = Math.floorDiv(px, C), cy0 = Math.floorDiv(py, C);
		double bestD = 1e9, bestDy = 0, bestR = 0;
		boolean bestGlow = false;
		for (int oy = -1; oy <= 1; oy++) {
			for (int ox = -1; ox <= 1; ox++) {
				int cx = cx0 + ox, cy = cy0 + oy;
				// Cap presence: the clump field sampled at this cell's centre,
				// widened by lush vegetation.
				double nwx = wx + (cx * C + C * 0.5 - (px + 0.5)) / 12.0;
				double nwy = wy + (cy * C + C * 0.5 - (py + 0.5)) / 12.0;
				if (Utils.noise2(nwx + 77, nwy + 55, 1.9) < 0.64 - 0.22 * veg) {
					continue;
				}
				double jx = cx * C + 1 + hash01(cx, cy, 31) * (C - 2);
				double jy = cy * C + 1 + hash01(cx, cy, 32) * (C - 2);
				double r = 1.4 + hash01(cx, cy, 33) * (0.5 + 0.9 * veg);
				double dx = px + 0.5 - jx, dy = py + 0.5 - jy;
				double d = Math.sqrt(dx * dx + dy * dy);
				if (d - r < bestD - bestR) {
					bestD = d;
					bestDy = dy;
					bestR = r;
					bestGlow = veg > 0.4 && hash01(cx, cy, 34) > 0.8;
				}
			}
		}
		if (bestD < bestR) {
			if (bestGlow && bestD < 0.75) {
				return FUNGUS_SPARK; // glowing cap core, a deliberate 1-2px accent
			}
			return RAMP[CLS_FUNGUS][bestDy < -0.2 * bestR ? 2 : 1]; // lit crown / flank
		}
		if (bestD < bestR + 1.1 && bestDy > 0) {
			return RAMP[CLS_FUNGUS][0]; // shadow rim grounding the cap's south edge
		}
		return RAMP[CLS_STONE][0]; // bare cave rock between beds
	}

	/**
	 * Scree: broken rock chips in 2x2-px chunks, dense enough to read as
	 * unstable ground but with a calm base so it does not shimmer.
	 */
	public static int rubble(int px, int py) {
		double r = hash01(px >> 1, py >> 1, 24);
		if (r < 0.24) {
			return RAMP[CLS_RUBBLE][0];
		}
		if (r > 0.90) {
			return RAMP[CLS_RUBBLE][2];
		}
		// 2-px grit between the chips (lone pixels would read as noise).
		return hash01(px >> 1, py, 25) < 0.10 ? RAMP[CLS_RUBBLE][0] : RAMP[CLS_RUBBLE][1];
	}

	/**
	 * Sand: a deliberately quiet surface -- sparse 2-px grain clusters (never
	 * lone pixels, which read as noise) over the base, crossed by sparse dark
	 * wind-ripple dashes (same dash grammar as water, far sparser).
	 */
	public static int sand(int px, int py) {
		int len = 4 + (int) (hash01(py, 0, 26) * 3);
		int seg = Math.floorDiv(px + (int) (hash01(py, 1, 27) * len), len);
		if (hash01(seg, py, 28) > 0.94) {
			return RAMP[CLS_SAND][0]; // wind-ripple dash
		}
		double g = hash01(px >> 1, py, 29); // 2-px horizontal grains
		return RAMP[CLS_SAND][g < 0.06 ? 0 : (g > 0.94 ? 2 : 1)];
	}

	/**
	 * Reed beds from above, as stamped tufts rather than pixel noise: each
	 * lattice cell holds (usually) one tuft -- a plus-shaped cluster of
	 * stalks, some grown taller into a vertical run, its centre tip catching
	 * the light -- over connected wet-dark ground showing between tufts. The
	 * repeated-motif-varied-placement grammar of hand-drawn foliage.
	 */
	public static int reeds(int px, int py) {
		int C = 4; // tuft lattice pitch, art-px
		int cx0 = Math.floorDiv(px, C), cy0 = Math.floorDiv(py, C);
		for (int oy = -1; oy <= 1; oy++) {
			for (int ox = -1; ox <= 1; ox++) {
				int cx = cx0 + ox, cy = cy0 + oy;
				if (hash01(cx, cy, 35) < 0.18) {
					continue; // open water gap in the bed
				}
				int tx = cx * C + 1 + (int) (hash01(cx, cy, 36) * (C - 2));
				int ty = cy * C + 1 + (int) (hash01(cx, cy, 37) * (C - 2));
				int dx = px - tx, dy = py - ty;
				int tall = hash01(cx, cy, 38) > 0.55 ? 2 : 1; // some stalks stand taller
				if (dx == 0 && dy == 0) {
					// A share of the tall stalks carry a brown cattail head
					// (the mud ramp's highlight, so no new colour).
					return tall == 2 && hash01(cx, cy, 51) > 0.7
							? RAMP[CLS_MUD][2] : RAMP[CLS_REEDS][2];
				}
				if ((Math.abs(dx) == 1 && dy == 0) || (dx == 0 && Math.abs(dy) <= tall)) {
					return RAMP[CLS_REEDS][1]; // the tuft's arms
				}
			}
		}
		return RAMP[CLS_REEDS][0]; // wet dark ground between tufts
	}

	/**
	 * One continuous water surface across the shallows AND the open water,
	 * driven by {@code d} -- the bilinear distance from true land (~1 on the
	 * walkable shallows band, ~2 at the first open-water tile, rising toward
	 * the middle). The sunlit turquoise darkens as it deepens, hands off to
	 * the water blues through a Bayer-dithered blend band, and from there the
	 * existing depth shading carries on down to the abyss tone. Both tile
	 * classes render through this same function, so the walkable/unwalkable
	 * boundary is invisible in the picture -- exactly like the deep-water
	 * gradient, one material all the way across.
	 */
	public static int waterSurface(double wx, double wy, int px, int py, double d) {
		double depth = Math.max(0, (d - 1.8) / 2.6);
		double blend = (d - 1.3) / 0.9; // 0 inner shallows .. 1 fully open water
		if (blend >= 1 || (blend > 0 && bayer(px, py) < blend)) {
			return waterTop(wx, wy, px, py, depth);
		}
		if (hash01(px >> 1, py, 62) > 0.985) {
			return RAMP[CLS_SHALLOWS][2]; // 2-px sparkle, denser than open water
		}
		double sh = Utils.noise2(wx + 9, wy + 3, 0.9);
		double p = (sh - 0.18) / 0.38 - (d - 1.0) * 1.2; // deeper wading = darker turquoise
		p = p < 0 ? 0 : (p > 1.9 ? 1.9 : p);
		return ditherRamp(CLS_SHALLOWS, p, px, py);
	}

	/**
	 * Quicksand: sodden, darker sand confined to the shadow/base shades so
	 * the pocket reads sunken against the pale pan around it, swirled by a
	 * coarse noise, with the rare 2-px surfacing bubble.
	 */
	public static int quicksand(double wx, double wy, int px, int py) {
		if (hash01(px >> 1, py, 63) > 0.988) {
			return RAMP[CLS_QUICKSAND][2]; // surfacing bubble
		}
		double sink = Utils.noise2(wx + 63, wy + 21, 0.8);
		double p = (sink - 0.25) / 0.4;
		p = p < 0 ? 0 : (p > 1 ? 1 : p);
		p = p < 0.33 ? 0 : (p > 0.66 ? 1 : (p - 0.33) / 0.33); // sharpen
		return ditherRamp(CLS_QUICKSAND, p, px, py);
	}

	/**
	 * Crystal cluster: stamped diamond shards (Manhattan-radius caps) packed
	 * over a dark mineral matrix. Each shard facets -- lit toward
	 * screen-north-west, mid on the far side, a dark cut edge on its rim --
	 * and some apexes carry the pale glint, the mineral counterpart of the
	 * fungus spark.
	 */
	public static int crystal(int px, int py) {
		int C = 4; // shard lattice pitch, art-px
		int cx0 = Math.floorDiv(px, C), cy0 = Math.floorDiv(py, C);
		for (int oy = -1; oy <= 1; oy++) {
			for (int ox = -1; ox <= 1; ox++) {
				int cx = cx0 + ox, cy = cy0 + oy;
				if (hash01(cx, cy, 74) > 0.8) {
					continue; // a gap of bare matrix breaks the lattice rhythm
				}
				int sx = cx * C + 1 + (int) (hash01(cx, cy, 64) * (C - 2));
				int sy = cy * C + 1 + (int) (hash01(cx, cy, 65) * (C - 2));
				int r = 2 + (int) (hash01(cx, cy, 66) * 2); // shard radius 2..3
				int dx = px - sx, dy = py - sy;
				int m = Math.abs(dx) + Math.abs(dy);
				if (m > r) {
					continue;
				}
				if (m == 0 && hash01(cx, cy, 67) > 0.6) {
					return CRYSTAL_SPARK; // glinting apex
				}
				if (m == r) {
					return RAMP[CLS_CRYSTAL][0]; // dark cut edge
				}
				return RAMP[CLS_CRYSTAL][dx + dy < 0 ? 2 : 1]; // lit / far facet
			}
		}
		return RAMP[CLS_CRYSTAL][0]; // dark matrix between shards
	}

	/**
	 * Geothermal vent: scorched sooty stone with a near-black throat at the
	 * tile centre and a dithered ember rim glowing around it -- the caves'
	 * warm accent beside the fungus' cold one. ({@code ai},{@code aj}) are
	 * tile-local art-pixel coordinates; the mouth sits per tile.
	 */
	public static int vent(int ai, int aj, int px, int py) {
		double d = Math.hypot(ai - 5.5, aj - 5.5);
		if (d < 2.2) {
			return RAMP[CLS_HOLE][0]; // the throat
		}
		if (d < 3.2) {
			return hash01(px, py, 68) < 0.5 ? VENT_EMBER : RAMP[CLS_VENT][0]; // ember rim
		}
		double g = hash01(px >> 1, py, 69);
		return RAMP[CLS_VENT][g < 0.12 ? 0 : (g > 0.93 ? 2 : 1)]; // sooty ground
	}

	/**
	 * The cross-section top of a man-made wall: running-bond masonry --
	 * 4-px courses of 6-px dressed blocks, joints staggered course to
	 * course, mortar in the shadow shade, each block hash-toned. The
	 * regularity IS the tell: nature striates, builders course.
	 */
	public static int wallTopBuilt(int px, int py, boolean litEdge) {
		int course = Math.floorDiv(py, 4);
		int off = (course & 1) == 0 ? 0 : 3; // running bond
		if (Math.floorMod(py, 4) == 0 || Math.floorMod(px + off, 6) == 0) {
			return RAMP[CLS_WALL_BUILT][0]; // mortar seam
		}
		// Blocks lean bright: the raised masonry catches the light, and the
		// tonal lift is what separates a wall top from the paving below it.
		int idx = hash01(Math.floorDiv(px + off, 6), course, 70) > 0.35 ? 2 : 1;
		if (litEdge) {
			idx = 2;
		}
		return RAMP[CLS_WALL_BUILT][idx];
	}

	/**
	 * The exposed south face of a man-made wall: tighter 3-px courses in the
	 * darker paved ramp, so the shadowed face reads as coursed stone rather
	 * than the natural wall's ragged dashes.
	 */
	public static int wallFaceBuilt(int px, int py) {
		int course = Math.floorDiv(py, 3);
		int off = (course & 1) == 0 ? 0 : 2;
		if (Math.floorMod(py, 3) == 0 || Math.floorMod(px + off, 4) == 0) {
			return RAMP[CLS_PAVED][0]; // joints
		}
		return RAMP[CLS_PAVED][hash01(Math.floorDiv(px + off, 4), course, 71) > 0.7 ? 2 : 1];
	}

	/**
	 * Paved corridor floor: large tile-sized slabs with shadow-shade joints,
	 * quiet worn grain, the odd sunken-dark slab and the odd diagonally
	 * cracked one. Deliberately calm and dark against the bright, busy
	 * masonry coursing of the walls: busy-wall/calm-floor is what makes a
	 * built interior read from above.
	 */
	public static int paved(int px, int py) {
		int S = 12; // slab size, art-px (one slab per tile)
		if (Math.floorMod(px, S) == 0 || Math.floorMod(py, S) == 0) {
			return RAMP[CLS_PAVED][0]; // joint
		}
		int sxi = Math.floorDiv(px, S), syi = Math.floorDiv(py, S);
		double slab = hash01(sxi, syi, 72);
		if (slab > 0.88 && Math.floorMod(px, S) == Math.floorMod(py, S)) {
			return RAMP[CLS_PAVED][0]; // cracked slab: diagonal fracture
		}
		double g = hash01(px >> 1, py, 73);
		int idx = g < 0.06 ? 0 : (g > 0.94 ? 2 : 1);
		if (slab < 0.12) {
			idx = 0; // a sunken, darker slab
		}
		return RAMP[CLS_PAVED][idx];
	}

	private static int darken(int rgb, double f) {
		int r = (int) (((rgb >> 16) & 255) * f);
		int g = (int) (((rgb >> 8) & 255) * f);
		int b = (int) ((rgb & 255) * f);
		return (r << 16) | (g << 8) | b;
	}

	/** Deterministic integer-lattice hash to [0,1). */
	public static double hash01(int x, int y, int s) {
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
