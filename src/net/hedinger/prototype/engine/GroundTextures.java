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
			CLS_CRYSTAL = 14, CLS_VENT = 15, CLS_WALL_BUILT = 16, CLS_PAVED = 17,
			CLS_PLATE = 18, CLS_CATWALK = 19, CLS_SHAFT = 20, CLS_PIPES = 21,
			CLS_AIRVENT = 22, CLS_CONCRETE = 23, CLS_STEELWALL = 24, CLS_DUCT = 25,
			CLS_CRYSTAL_BED = 26, CLS_CRYSTAL_SPARSE = 27, CLS_SWITCH = 28, CLS_DOCK = 29,
			CLS_ROCKY = 30, CLS_SLUDGE = 31;
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
			{ 0x353a42, 0x515862, 0x707885 }, // facility deck plate (cool steel)
			{ 0x272b33, 0x454b56, 0x646b78 }, // catwalk grate metal
			{ 0x0a0b10, 0x16181f, 0x232630 }, // shaft void
			{ 0x2e3d3a, 0x4a615c, 0x6b8781 }, // pipes (industrial green-grey)
			{ 0x2c3037, 0x474d57, 0x656c79 }, // air-vent housing
			{ 0x45464a, 0x64656a, 0x86878c }, // poured concrete (neutral grey)
			{ 0x30343d, 0x4d535f, 0x6e7583 }, // steel bulkhead wall
			{ 0x3f454c, 0x616974, 0x88929e }, // galvanised duct metal (brightest)
			{ 0x1f2637, 0x38466e, 0x5f74a4 }, // crystal bed (grounded, a step darker)
			{ 0x2c313e, 0x464c5e, 0x646c82 }, // sparse shards (cave stone, cool cast)
			{ 0x353a42, 0x515862, 0x707885 }, // switch plate (deck steel family)
			{ 0x2a2f38, 0x424a57, 0x5d6675 }, // charge dock (deck steel, a shade colder)
			// Rocky grassland's grit: the bridge between meadow earth and bare
			// rock. Deliberately BETWEEN its two neighbours — the soil ramp
			// desaturated and lifted toward the stone's grey, but staying warm
			// enough that it never reads as the cave's cold blue floor. The
			// slabs bedded in it are drawn from CLS_STONE itself, so a rocky
			// tile's rock IS the rock floor's rock.
			{ 0x4a4338, 0x6b6353, 0x8f8776 }, // rocky grassland (dusty grit)
			{ 0x2a4a16, 0x548f22, 0x86c832 }, // waste sludge (acid green, never pit-dark)
	};
	/** The design system's cover translucency: every concealment veil — the
	 *  thicket canopy, reed stalks, the duct's ribbed lid — draws its
	 *  re-stamped pixels at 25% translucency, so a veiled body always
	 *  half-reads through its cover. One global constant, shared with the web
	 *  client (VEIL_ALPHA in render.ts) and documented in ART-STYLE.md §4. */
	public static final float VEIL_ALPHA = 0.75f;

	/** Facility accents: hazard striping for anything that drops or crushes,
	 *  and a rust bloom for weathered pipework. */
	private static final int HAZARD = 0xd8b028, HAZARD_DARK = 0x17171a, RUST = 0x8a5a34;
	/** The one colour allowed above the sludge ramp: an acid bloom on a
	 *  surfacing bubble. Kept distinct from the fungus spark (0x9df5c6) on
	 *  purpose -- the caves glow because they are alive, the spill glows
	 *  because it is not, and the two must never read as the same thing. */
	private static final int TOXIC = 0xb6f04a;
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
		return cls == CLS_WALL || cls == CLS_WALL_BUILT || cls == CLS_CRYSTAL
				|| cls == CLS_CONCRETE || cls == CLS_STEELWALL;
	}

	/** Terrain colour class for a tile: ground, structure, or -1 (ramp).
	 *  Type-driven: a tile type has ONE class, so autotiling and edge laps
	 *  never depend on live state. Live vegetation is not part of the ground's
	 *  identity — it lives in the sprite layer stamped on top (see the web
	 *  client's vegetation layer / server VegFeed). The one static scalar the
	 *  pixel pass adds is grassland's fertility potential (see {@link #sward}),
	 *  fixed at world-gen and therefore bakeable. */
	public static int groundClass(Tile t) {
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
		case TYPE_CRYSTAL_BED:
			return CLS_CRYSTAL_BED;
		case TYPE_CRYSTAL_SPARSE:
			return CLS_CRYSTAL_SPARSE;
		case TYPE_SWITCH:
			return CLS_SWITCH;
		case TYPE_DOCK:
			return CLS_DOCK;
		case TYPE_SLUDGE:
			return CLS_SLUDGE;
		case TYPE_VENT:
			return CLS_VENT;
		case TYPE_WALL_BUILT:
			return CLS_WALL_BUILT;
		case TYPE_PAVED:
			return CLS_PAVED;
		case TYPE_PLATE:
			return CLS_PLATE;
		case TYPE_CATWALK:
			return CLS_CATWALK;
		case TYPE_SHAFT:
			return CLS_SHAFT;
		case TYPE_PIPES:
			return CLS_PIPES;
		case TYPE_AIRVENT:
			return CLS_AIRVENT;
		case TYPE_WALL_CONCRETE:
			return CLS_CONCRETE;
		case TYPE_WALL_STEEL:
			return CLS_STEELWALL;
		case TYPE_DUCT:
			return CLS_DUCT;
		case TYPE_ROCKY:
			return CLS_ROCKY; // slabs bedded in grit; its sward is drawn from fertility too
		case TYPE_FLOOR:
			// The living substrate. Its class is earth (for autotiling rank);
			// the pixel pass paints it as sward(fertility) — green where the
			// ground CAN be lush, dirt where it cannot. The standing crop is
			// still the sprite layer's story.
			return CLS_SOIL;
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

	// ---- grassland sward ---------------------------------------------------

	/** Fertility below which grassland bakes as bare dry earth: the badlands'
	 *  zero-fertility dirt keeps its sun-cracked clay look. */
	public static final double SWARD_BARE = 0.15;
	/** Fertility at which the baked sward closes into unbroken green — the
	 *  richest meadows read fully lush, poorer ground grades down from there. */
	public static final double SWARD_FULL = 0.75;

	/**
	 * Grassland ground at its POTENTIAL: the baked sward's green coverage is
	 * the tile's fertility — the cap on what can grow there — not the standing
	 * crop, which stays the live vegetation sprite layer stamped on top.
	 * Below {@link #SWARD_BARE} the substrate reads as the dry cracked clay it
	 * is; across the window the sward closes from scattered flecks through
	 * clumped patches to unbroken green at {@link #SWARD_FULL}, so a rich
	 * meadow looks rich before a single blade of its crop is drawn. Fertility
	 * is a world-gen field (nutrient closure drifts it up slowly; a bake
	 * snapshots genesis), so the ground stays bakeable: type + fertility in,
	 * one deterministic picture out.
	 */
	public static int sward(double fert, double wx, double wy, int px, int py) {
		double cover = (fert - SWARD_BARE) / (SWARD_FULL - SWARD_BARE);
		cover = cover < 0 ? 0 : (cover > 1 ? 1 : cover);
		if (cover > 0) {
			// Clumped, not sprinkled: a fine world-space field gathers the
			// grass into patches whose share grows with coverage, and Bayer
			// dithers each patch border — the mix never leaves the two ramps.
			double clump = Utils.noise2(wx + 57, wy + 91, 1.6);
			double local = cover * 1.5 + (clump - 0.5) * 0.7 - 0.25;
			if (local > bayer(px, py)) {
				return grassPixel(fert, wx, wy, px, py);
			}
		}
		// The earth between the grass: the quiet soil interior, sun-cracked
		// into clay plates only where the ground is genuinely dry — a closed
		// sward's rare soil pockets are moist shadow, not badlands.
		if (cover < 0.35 && crack(wx, wy, 0.38, 0.05)) {
			return RAMP[CLS_SOIL][0];
		}
		return quietGround(CLS_SOIL, wx, wy, px, py);
	}

	/** One pixel of living sward: grass-ramp mottle self-shaded by a coarse
	 *  world-space light field, sparse 2-px blade-tip glints, and on the
	 *  richest ground the odd meadow blossom — the same red/cream accents the
	 *  shrubs and thickets wear, so all the flora reads as one family. */
	private static int grassPixel(double fert, double wx, double wy, int px, int py) {
		if (fert > 0.8 && hash01(px, py, 93) > 0.9965) {
			return hash01(px, py, 94) > 0.45 ? BLOOM_RED : BLOOM_CREAM;
		}
		double g = hash01(px >> 1, py, 47); // 2-px grain clusters, quietGround's grammar
		if (g < 0.045) {
			return RAMP[CLS_GRASS][0];
		}
		if (g > 0.97) {
			return RAMP[CLS_GRASS][2];
		}
		double sh = Utils.noise2(wx + 23, wy + 47, 1.1);
		double p = (sh - 0.30) / 0.42;
		p = p < 0 ? 0 : (p > 1 ? 1 : p);
		p = p < 0.33 ? 0 : (p > 0.66 ? 1 : (p - 0.33) / 0.33); // sharpen
		return ditherRamp(CLS_GRASS, p, px, py);
	}

	/**
	 * Rocky grassland: broad stone slabs bedded in dusty grit, with what grass
	 * the thin ground can keep clinging around their edges.
	 *
	 * <p>Three materials, and only one of them is new. The slabs are drawn from
	 * {@link #CLS_STONE} — the rock-floor ramp itself, so a slab here and a
	 * stone-floor tile next door are the same rock — and the grass is
	 * {@link #grassPixel}, the same blades the meadow grows. Only the grit
	 * between them has its own ramp, and that one is built as the bridge
	 * between the two. The tile therefore reads as a PLACE where meadow and
	 * rock meet, not as a third biome with a palette of its own.
	 *
	 * <p>The grass gets a shelter bonus close to a slab: on thin ground the
	 * blades come up in the lee of the stones, where the runoff collects and
	 * nothing can trample them flat, which is what makes a rocky sward read as
	 * rocky rather than merely patchy.
	 */
	public static int rockyGround(double fert, double wx, double wy, int px, int py) {
		// Slabs on a jittered lattice: the repeated-motif-varied-placement
		// grammar the canopy and the reed beds use, at stone scale. Each plate
		// is FACETED rather than round — its radius steps per angular sector,
		// so the outline breaks into straight runs and corners the way split
		// rock does. A smooth ellipse here reads as a pebble, and a field of
		// identical pebbles reads as procedural; broken edges read as bedrock.
		// Pitch and radius are set so a stone is a FRACTION of a body, not a
		// match for one: the ground should read as stony, and a rock drawn at
		// creature scale stops being ground and starts being an obstacle the
		// eye expects to walk around.
		int C = 8; // stone lattice pitch, art-px
		int cx0 = Math.floorDiv(px, C), cy0 = Math.floorDiv(py, C);
		double bestD = 1e9, bestDy = 0, bestR = 0;
		boolean bestPale = false;
		for (int oy = -1; oy <= 1; oy++) {
			for (int ox = -1; ox <= 1; ox++) {
				int cx = cx0 + ox, cy = cy0 + oy;
				if (hash01(cx, cy, 96) < 0.48) {
					continue; // open grit between the stones: most of the ground
				}
				double jx = cx * C + 1 + hash01(cx, cy, 97) * (C - 2);
				double jy = cy * C + 1 + hash01(cx, cy, 98) * (C - 2);
				double r = 1.3 + hash01(cx, cy, 99) * 2.1;
				// Each stone lies its own way: some squat, some long.
				double squash = 0.66 + hash01(cx, cy, 93) * 0.5;
				double dx = (px + 0.5 - jx) * squash, dy = (py + 0.5 - jy) * 1.3;
				double d = Math.sqrt(dx * dx + dy * dy);
				int sector = (int) ((Math.atan2(dy, dx) + Math.PI) * (5 / (2 * Math.PI)));
				double rr = r * (0.78 + 0.42 * hash01(cx * 5 + sector, cy, 94));
				if (d - rr < bestD - bestR) {
					bestD = d;
					bestDy = py + 0.5 - jy;
					bestR = rr;
					// Stones weather differently: some sit pale in the light,
					// others stay dark. Without this a rock field is one colour.
					bestPale = hash01(cx, cy, 95) > 0.42;
				}
			}
		}
		if (bestD < bestR) {
			// At three or four pixels across a stone is a SHAPE, not a surface:
			// a lit crown, a body, and the shadow below. Texture inside it —
			// the foliation banding a tile-wide slab could carry — would only
			// read as speckle at this size, so only the rare big one gets it.
			if (bestDy < -0.4 * bestR) {
				return RAMP[CLS_STONE][2]; // the crown catches the light
			}
			int body = bestPale ? 2 : 1;
			if (bestR > 2.6 && Utils.noise2(wx * 3.4 + 61, wy * 0.8 + 17, 2.2) > 0.62) {
				return RAMP[CLS_STONE][bestPale ? 1 : 0]; // layering, on the big ones only
			}
			return RAMP[CLS_STONE][body];
		}
		if (bestD < bestR + 0.8 && bestDy > 0) {
			return RAMP[CLS_ROCKY][0]; // contact shadow grounding the stone's south edge
		}
		// Off the stone: what the thin ground can grow, then the grit itself.
		double cover = (fert - SWARD_BARE) / (SWARD_FULL - SWARD_BARE);
		cover = cover < 0 ? 0 : (cover > 1 ? 1 : cover);
		if (cover > 0) {
			double clump = Utils.noise2(wx + 57, wy + 91, 1.6);
			double shelter = bestD - bestR < 2.5 ? 0.26 : 0.0; // the lee of a stone
			double local = cover * 1.3 + shelter + (clump - 0.5) * 0.8 - 0.30;
			if (local > bayer(px, py)) {
				return grassPixel(fert, wx, wy, px, py);
			}
		}
		if (hash01(px >> 1, py, 88) > 0.988) {
			return RAMP[CLS_SAND][2]; // a dead stalk bleached on the grit
		}
		if (hash01(px, py, 90) > 0.99) {
			return RAMP[CLS_STONE][2]; // a chip broken off the slabs
		}
		return quietGround(CLS_ROCKY, wx, wy, px, py);
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

	/** Prism lattice pitch for the dense formation (art-px). */
	private static final int XTAL_C = 6;

	/**
	 * A dense crystal formation: a thicket of big standing prisms grown from
	 * the cave floor -- NOT a wall. Each prism head is its own faceted
	 * diamond (lit north-west facet, mid flanks, shadowed south wedge, dark
	 * cut rim, glinting apex), some carry a raised tip plateau for height,
	 * and each drops a short contact shadow onto the ground south of it. The
	 * ground showing in the crevices IS the stone floor, so the silhouette
	 * stays jagged prism-by-prism -- impassable because the prisms stand
	 * shoulder to shoulder, not because a mass has a face.
	 */
	public static int crystal(double wx, double wy, int px, int py) {
		int c = crystalPrism(px, py);
		if (c >= 0) {
			return c;
		}
		int ground = quietGround(CLS_STONE, wx, wy, px, py);
		// Each prism's own cast shadow: two softening steps of ground south
		// of any head -- per-prism depth in place of a wall's graded band.
		if (crystalPrism(px, py - 1) >= 0) {
			return darken(ground, 0.55);
		}
		if (crystalPrism(px, py - 2) >= 0) {
			return darken(ground, 0.75);
		}
		return ground;
	}

	/** The facet colour of the formation prism covering this art-pixel, or
	 *  -1 where none does (a crevice between prisms). */
	private static int crystalPrism(int px, int py) {
		int cx0 = Math.floorDiv(px, XTAL_C), cy0 = Math.floorDiv(py, XTAL_C);
		int bestM = Integer.MAX_VALUE;
		int bestR = -1, bestCx = 0, bestCy = 0, bestDx = 0, bestDy = 0;
		for (int oy = -1; oy <= 1; oy++) {
			for (int ox = -1; ox <= 1; ox++) {
				int cx = cx0 + ox, cy = cy0 + oy;
				if (hash01(cx, cy, 74) > 0.94) {
					continue; // the odd missing prism keeps the thicket jagged
				}
				int sx = cx * XTAL_C + 1 + (int) (hash01(cx, cy, 64) * (XTAL_C - 2));
				int sy = cy * XTAL_C + 1 + (int) (hash01(cx, cy, 65) * (XTAL_C - 2));
				int r = 3 + (int) (hash01(cx, cy, 66) * 1.99); // head radius 3..4
				int dx = px - sx, dy = py - sy;
				int m = Math.abs(dx) + Math.abs(dy);
				if (m <= r && m < bestM) { // nearest head owns the pixel
					bestM = m;
					bestR = r;
					bestCx = cx;
					bestCy = cy;
					bestDx = dx;
					bestDy = dy;
				}
			}
		}
		if (bestR < 0) {
			return -1;
		}
		if (bestM == 0 && hash01(bestCx, bestCy, 67) > 0.45) {
			return CRYSTAL_SPARK; // glinting apex
		}
		if (bestM == bestR) {
			return RAMP[CLS_CRYSTAL][0]; // dark cut rim
		}
		// Diagonal facet split to one light source: NW catches the light, the
		// flanks hold the mid tone, the south wedge falls dark.
		int idx = bestDx + bestDy < 0 ? 2
				: (bestDy > 0 && Math.abs(bestDy) > Math.abs(bestDx) ? 0 : 1);
		if (bestM <= bestR - 3 && hash01(bestCx, bestCy, 72) > 0.5) {
			idx = Math.min(2, idx + 1); // a raised tip plateau: the tall prisms
		}
		if (hash01(bestCx, bestCy, 68) > 0.8) {
			idx = Math.min(2, idx + 1); // an occasional paler prism
		}
		return RAMP[CLS_CRYSTAL][idx];
	}

	/**
	 * A packed crystal bed: the cave floor grown thick with knee-high shard
	 * diamonds -- the walkable-but-slow middle density. The ground between
	 * shards IS the stone floor (so the bed reads as passable ground, not a
	 * pit), and the shards are big enough to count: radius-2 diamonds with
	 * the formation's facet grammar, packing most of the lattice.
	 */
	public static int crystalBed(double wx, double wy, int px, int py) {
		int C = 5; // shard lattice pitch, art-px
		int cx0 = Math.floorDiv(px, C), cy0 = Math.floorDiv(py, C);
		for (int oy = -1; oy <= 1; oy++) {
			for (int ox = -1; ox <= 1; ox++) {
				int cx = cx0 + ox, cy = cy0 + oy;
				if (hash01(cx, cy, 74) > 0.9) {
					continue; // the odd bare gap keeps the bed from tiling
				}
				int sx = cx * C + 1 + (int) (hash01(cx, cy, 64) * (C - 2));
				int sy = cy * C + 1 + (int) (hash01(cx, cy, 65) * (C - 2));
				int dx = px - sx, dy = py - sy;
				int m = Math.abs(dx) + Math.abs(dy);
				if (m > 2) {
					continue; // readable diamonds, all one size
				}
				if (m == 0 && hash01(cx, cy, 67) > 0.8) {
					return CRYSTAL_SPARK; // the odd glinting apex
				}
				if (m == 2) {
					return RAMP[CLS_CRYSTAL_BED][0]; // dark cut edge
				}
				return RAMP[CLS_CRYSTAL_BED][dx + dy < 0 ? 2 : 1]; // lit / far facet
			}
		}
		return quietGround(CLS_STONE, wx, wy, px, py); // floor between shards
	}

	/**
	 * Sparse shards: ordinary cave-stone ground with the occasional small
	 * crystal cluster grown through it -- fully walkable, and blending
	 * seamlessly into plain stone because the ground IS plain stone. The
	 * shards reuse the bed's facet grammar at cluster scale.
	 */
	public static int crystalSparse(double wx, double wy, int px, int py) {
		int C = 6; // wide lattice: clusters are the exception, not the rule
		int cx0 = Math.floorDiv(px, C), cy0 = Math.floorDiv(py, C);
		for (int oy = -1; oy <= 1; oy++) {
			for (int ox = -1; ox <= 1; ox++) {
				int cx = cx0 + ox, cy = cy0 + oy;
				if (hash01(cx, cy, 71) > 0.30) {
					continue; // most lattice cells grow nothing
				}
				int sx = cx * C + 1 + (int) (hash01(cx, cy, 64) * (C - 2));
				int sy = cy * C + 1 + (int) (hash01(cx, cy, 65) * (C - 2));
				int r = 1 + (int) (hash01(cx, cy, 66) * 1.99); // shard radius 1..2
				int dx = px - sx, dy = py - sy;
				int m = Math.abs(dx) + Math.abs(dy);
				if (m > r) {
					continue;
				}
				if (m == 0 && hash01(cx, cy, 67) > 0.8) {
					return CRYSTAL_SPARK; // glinting apex
				}
				if (m == r) {
					return RAMP[CLS_CRYSTAL_BED][0]; // dark cut edge
				}
				return RAMP[CLS_CRYSTAL_BED][dx + dy < 0 ? 2 : 1]; // lit / far facet
			}
		}
		return quietGround(CLS_STONE, wx, wy, px, py); // the cave floor itself
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

	/**
	 * The cross-section top of a poured concrete wall: broad 6-px form
	 * panels separated by faint seams, a form-tie dimple in each panel, and
	 * sparse 2-px weather stains -- utilitarian and calm, the mass the
	 * facility is carved from.
	 */
	public static int concreteTop(int px, int py, boolean litEdge) {
		int S = 6;
		if (Math.floorMod(px, S) == 0 || Math.floorMod(py, S) == 0) {
			return RAMP[CLS_CONCRETE][0]; // form seam
		}
		if (Math.floorMod(px, S) == 3 && Math.floorMod(py, S) == 3) {
			return RAMP[CLS_CONCRETE][0]; // form-tie dimple
		}
		if (litEdge) {
			return RAMP[CLS_CONCRETE][2];
		}
		return hash01(px >> 1, py, 84) < 0.07 ? RAMP[CLS_CONCRETE][0] : RAMP[CLS_CONCRETE][1];
	}

	/**
	 * The exposed south face of a concrete wall: low-contrast vertical drip
	 * streaks in the dark half of the ramp -- weathered pour lines, quieter
	 * than natural rock's carved dashes.
	 */
	public static int concreteFace(int px, int py) {
		int len = 5 + (int) (hash01(px, 0, 85) * 4);
		int phase = (int) (hash01(px, 1, 86) * len);
		int seg = Math.floorDiv(py + phase, len);
		return RAMP[CLS_CONCRETE][hash01(px, seg, 87) < 0.55 ? 0 : 1];
	}

	/**
	 * The cross-section top of a steel bulkhead: large 6-px riveted panels
	 * -- rivets marching along the seams, not just the corners -- darker
	 * than the deck so the wall mass reads heavy against the floor.
	 */
	public static int steelTop(int px, int py, boolean litEdge) {
		int S = 6;
		int mx = Math.floorMod(px, S), my = Math.floorMod(py, S);
		if (mx == 0 || my == 0) {
			// Seam, studded with rivets every third pixel.
			return (mx == 0 && my % 3 == 1) || (my == 0 && mx % 3 == 1)
					? RAMP[CLS_STEELWALL][2] : RAMP[CLS_STEELWALL][0];
		}
		if (litEdge) {
			return RAMP[CLS_STEELWALL][2];
		}
		return hash01(px >> 1, py, 88) < 0.05 ? RAMP[CLS_STEELWALL][0] : RAMP[CLS_STEELWALL][1];
	}

	/**
	 * The exposed south face of a steel bulkhead: corrugated -- hard 3-px
	 * vertical ribs cycling shadow/body/lit, the machine-regular counterpart
	 * of concrete's drip streaks.
	 */
	public static int steelFace(int px, int py) {
		return RAMP[CLS_STEELWALL][Math.floorMod(px, 3)];
	}

	/**
	 * The open channel of a crawl duct (its lid is drawn separately, over
	 * whatever crawls inside): galvanised sheet floor with side walls along
	 * the run and cross-seams every 6 px.
	 */
	public static int ductChannel(int along, int across, int px, int py) {
		if (across == 0 || across == 11) {
			return RAMP[CLS_DUCT][0]; // the duct's side walls
		}
		if (across == 1 || across == 10) {
			return RAMP[CLS_DUCT][1]; // inner wall face
		}
		if (Math.floorMod(along, 6) == 0) {
			return RAMP[CLS_DUCT][0]; // sheet seam
		}
		return hash01(px >> 1, py, 89) > 0.94 ? RAMP[CLS_DUCT][2] : RAMP[CLS_DUCT][1];
	}

	/**
	 * The duct's ribbed lid, re-stamped over anything crawling inside (the
	 * concealment pass): bright cross-ribs with slotted gaps, so a crawler
	 * reads as glimpses between ribs -- the facility's answer to the thicket
	 * canopy. Returns null in the slots.
	 */
	public static Integer ductLid(int along, int across, int px, int py) {
		if (across == 0 || across == 11) {
			return RAMP[CLS_DUCT][0]; // side walls stay solid
		}
		int m = Math.floorMod(along, 3);
		if (m == 0) {
			return RAMP[CLS_DUCT][2]; // lit rib
		}
		if (m == 1) {
			return RAMP[CLS_DUCT][1]; // rib flank
		}
		return null; // the slot between ribs: the crawler shows through
	}

	/**
	 * Facility deck plating: 6-px steel plates with shadow seams, a dark
	 * rivet sunk at each plate corner, and diagonal tread ribs on a share of
	 * the plates -- the workhorse floor of the base, quiet enough for
	 * everything else to sit on.
	 */
	public static int plate(int px, int py) {
		int S = 6;
		int mx = Math.floorMod(px, S), my = Math.floorMod(py, S);
		if (mx == 0 || my == 0) {
			return RAMP[CLS_PLATE][0]; // plate seam
		}
		if (mx == 1 && my == 1) {
			return RAMP[CLS_PLATE][0]; // corner rivet
		}
		int sxi = Math.floorDiv(px, S), syi = Math.floorDiv(py, S);
		double sp = hash01(sxi, syi, 80);
		if (sp > 0.7 && (mx + my) % 3 == 0) {
			return RAMP[CLS_PLATE][2]; // tread rib on this plate
		}
		return hash01(px >> 1, py, 81) < 0.06 ? RAMP[CLS_PLATE][0] : RAMP[CLS_PLATE][1];
	}

	/**
	 * Catwalk grating for art-pixel (i along the run, j across it): solid
	 * side rails with a lit outer edge, cross-treads every second pixel --
	 * and null in the gaps, so whatever lies a level below shows through the
	 * grate exactly as it does through a pit.
	 */
	public static Integer catwalk(int along, int across, int px, int py) {
		if (across <= 1 || across >= 10) {
			return across == 0 || across == 11
					? RAMP[CLS_CATWALK][2] : RAMP[CLS_CATWALK][1]; // rails, lit outer edge
		}
		if (Math.floorMod(along, 2) == 0) {
			return hash01(px, py, 82) < 0.08
					? RAMP[CLS_CATWALK][0] : RAMP[CLS_CATWALK][1]; // tread bar, the odd worn px
		}
		return null; // the gap: the level below shows through
	}

	/** Hazard striping (diagonal yellow/black) for a drop or crush edge. */
	public static int hazardStripe(int px, int py) {
		return Math.floorMod((px + py) / 2, 2) == 0 ? HAZARD : HAZARD_DARK;
	}

	/**
	 * Pipe run along one axis at art-pixel (along, across): two parallel
	 * pipes, each lit on its leading edge, with darker flange rings every
	 * 6 px and the odd rust bloom -- drawn over deck plating.
	 */
	public static Integer pipeRun(int along, int across, int px, int py) {
		boolean pipeA = across == 3 || across == 4;
		boolean pipeB = across == 7 || across == 8;
		if (!pipeA && !pipeB) {
			return null; // deck shows between and beside the pipes
		}
		boolean flange = Math.floorMod(along, 6) == 0;
		if (flange) {
			return RAMP[CLS_PIPES][0]; // flange ring
		}
		if (hash01(px >> 1, py >> 1, 83) > 0.93) {
			return RUST; // weathering bloom
		}
		boolean lit = across == 3 || across == 7; // lit edge of each cylinder
		return RAMP[CLS_PIPES][lit ? 2 : 1];
	}

	/**
	 * Floor-mounted ventilation grille: deck plating with a centred housing,
	 * louver slats alternating dark and mid inside a framed square, and a
	 * screw glint at each frame corner.
	 */
	public static int airVent(int ai, int aj, int px, int py) {
		boolean inFrame = ai >= 2 && ai <= 9 && aj >= 2 && aj <= 9;
		if (!inFrame) {
			return plate(px, py); // the surrounding deck
		}
		boolean rim = ai == 2 || ai == 9 || aj == 2 || aj == 9;
		if (rim) {
			boolean corner = (ai == 2 || ai == 9) && (aj == 2 || aj == 9);
			return corner ? RAMP[CLS_AIRVENT][2]
					: aj == 2 ? lighten2(RAMP[CLS_AIRVENT][2], 1.15) // lit north frame
					: RAMP[CLS_AIRVENT][1];
		}
		// Louvers with real depth: near-black slots between lit slat edges, so
		// the grille reads as an opening in the deck, not a patch of it.
		return Math.floorMod(aj, 2) == 0
				? darken(RAMP[CLS_AIRVENT][0], 0.5) : RAMP[CLS_AIRVENT][2];
	}

	/**
	 * A switch pedestal tile, test-chamber style: a broad pale circular base
	 * plate nearly filling the tile -- polished concrete, lit on its north
	 * arc -- around a dark circular seat where the control sits. The base is
	 * the socket only: the red control itself (a plate's broad disc or a
	 * button's domed cap, up or pressed) is drawn live by the {@code Switch}
	 * entity, since baked ground cannot animate.
	 */
	public static int switchPlate(int ai, int aj, int px, int py) {
		double dx = ai - 5.5, dy = aj - 5.5; // centre of the 12-px tile
		double d2 = dx * dx + dy * dy;
		if (d2 > 27) {
			return plate(px, py); // deck showing at the corners
		}
		if (d2 > 16) {
			// The pale base ring, shaded to the one light source.
			return dy < -Math.abs(dx) * 0.5 ? lighten2(RAMP[CLS_CONCRETE][2], 1.12)
					: dy > Math.abs(dx) * 0.5 ? RAMP[CLS_CONCRETE][1]
					: RAMP[CLS_CONCRETE][2];
		}
		if (d2 > 12) {
			return RAMP[CLS_SWITCH][0]; // the dark seam between base and seat
		}
		return darken(RAMP[CLS_SWITCH][0], 0.8); // the recessed seat
	}

	/**
	 * The steward drone's charge dock: a recessed berth sunk into the deck,
	 * hazard-striped on its two open sides and lit by a ring of contacts the
	 * drone settles onto. Baked, so nothing here animates -- the berth reads
	 * as occupied or empty from the drone parked over it, not from the pad.
	 *
	 * <p>Drawn on the same 12-per-tile art-pixel grid as the switch seat and
	 * the same deck-steel family, so a dock reads as facility furniture
	 * rather than as a new material: the eye should place it beside the vent
	 * grille and the pressure plate, not beside the crystal.
	 */
	public static int chargeDock(int ai, int aj, int px, int py) {
		// The hazard border: the outermost art-pixel ring, striped on the
		// diagonal like every other "machinery works here" marking in the
		// facility, so the berth is legible as a keep-clear square.
		if (ai == 0 || aj == 0 || ai == 11 || aj == 11) {
			return ((ai + aj) & 3) < 2 ? HAZARD : HAZARD_DARK;
		}
		double dx = ai - 5.5, dy = aj - 5.5;
		double d2 = dx * dx + dy * dy;
		if (d2 > 20) {
			return plate(px, py); // ordinary deck showing inside the stripes
		}
		if (d2 > 12) {
			// The contact ring, shaded to the world's one light source: lit on
			// the north arc, in shadow on the south, mid on the flanks.
			return dy < -Math.abs(dx) * 0.5 ? lighten2(RAMP[CLS_DOCK][2], 1.2)
					: dy > Math.abs(dx) * 0.5 ? RAMP[CLS_DOCK][0]
					: RAMP[CLS_DOCK][2];
		}
		if (d2 > 8) {
			return RAMP[CLS_DOCK][0]; // the seam around the sunken berth
		}
		// The berth floor, with the charge coil's faint glow at dead centre --
		// the one warm pixel on the pad, so a dock is findable at a glance.
		return d2 < 2 ? HAZARD : darken(RAMP[CLS_DOCK][0], 0.75);
	}

	/**
	 * A waste channel: the facility's spill, pooled and going nowhere.
	 *
	 * <p>Built from the same grammar as quicksand -- a low-frequency noise
	 * field dithered across the ramp, with hash-gated bubbles surfacing -- for
	 * the good reason that they are the same kind of thing: a treacherous
	 * liquid ground you can walk into. What separates them is entirely
	 * palette and rate. Sludge is thicker, so the noise is coarser and the
	 * pools read as slabs of colour rather than a gradient; it is fouler, so
	 * the bubbles are twice as common and a few carry the acid bloom.
	 *
	 * <p>The scum crust along the top of each pool is the one detail doing
	 * real work: a flat green field reads as painted floor, and the crust is
	 * what says liquid.
	 */
	public static int sludge(double wx, double wy, int px, int py) {
		double pool = Utils.noise2(wx + 141, wy + 87, 0.55);
		// A surfacing bubble, and occasionally one bright enough to be a
		// warning. 2-px clusters, never lone pixels -- see quietGround.
		double b = hash01(px >> 1, py >> 1, 74);
		if (b > 0.982) {
			return pool > 0.5 ? TOXIC : RAMP[CLS_SLUDGE][2];
		}
		// Scum crust: where the pool field crosses its own edge going north,
		// a 1-px lip of the pale shade. The eye reads it as a meniscus.
		double above = Utils.noise2(wx + 141, wy + 87 - 0.09, 0.55);
		if (pool > 0.5 && above <= 0.5) {
			return RAMP[CLS_SLUDGE][2];
		}
		double p = (pool - 0.3) / 0.4;
		p = p < 0 ? 0 : (p > 1 ? 1 : p);
		return ditherRamp(CLS_SLUDGE, p, px, py);
	}

	private static int lighten2(int rgb, double f) {
		int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
		int g = Math.min(255, (int) (((rgb >> 8) & 255) * f));
		int b = Math.min(255, (int) ((rgb & 255) * f));
		return (r << 16) | (g << 8) | b;
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
