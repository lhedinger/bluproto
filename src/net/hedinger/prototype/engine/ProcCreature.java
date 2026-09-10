package net.hedinger.prototype.engine;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;

import net.hedinger.prototype.entities.Genome;

/**
 * Procedurally drawn top-down organism: a dorsal silhouette oriented to a
 * heading, lit from screen-north, with appendages splaying outward. The whole
 * creature is generated from a small {@link Phenotype} (which is derived from a
 * {@link Genome}, so appearance is heritable) and drawn straight to a
 * {@link Graphics2D} as chunky art-pixels at any screen position and size.
 *
 * <p>Rotation is free (the heading basis), animation is a phase clock, and
 * actions are a form-agnostic {@link Mod} transform envelope layered on top --
 * one system animates every body form. See the ArtStyleDemo prototype for the
 * design exploration this was lifted from.
 */
public final class ProcCreature {

	public static final int A_IDLE = 0, A_LUNGE = 1, A_HURT = 2, A_EAT = 3, A_COURT = 4,
			A_ALARM = 5, A_DEATH = 6, A_SPAWN = 7;

	private ProcCreature() {
	}

	/** Heritable appearance: silhouette form + variant + features + size, plus colour. */
	public static final class Phenotype {
		public int color, form, legs, core, pattern, r;
		/** Which of the plan's {@link #VARIANTS} bodies this is (0..7). */
		public int variant;
		public boolean antennae, tail, flying;
	}

	/** Bodies per worn plan, not counting colour. */
	public static final int VARIANTS = 8;

	/**
	 * The eight bodies of each plan, by name, indexed by {@link Phenotype#variant}.
	 * Rows follow {@code form}. The two unworn plans (2 and 5) have no variants
	 * and repeat their one name, so any index reads sensibly.
	 *
	 * <p>Every variant is an OUTLINE difference, because outline is the only
	 * thing that survives being drawn at the size a creature is actually watched
	 * (see form 4's comment below). Marks, leg counts and colour were all there
	 * before this, and measured on a settled world they left a clade wearing what
	 * read as one body: a herd of two hundred grazers was one shape in a dozen
	 * greens. Eight silhouettes a clade is enough that a lineage looks like a
	 * lineage rather than a colour.
	 */
	public static final String[][] VARIANT_NAMES = {
			{ "grazer", "long", "round", "horned", "broad", "stub-tailed", "frilled", "waisted" },
			{ "ciliate", "bare", "spiked", "oval", "hooked", "twin", "haloed", "flat" },
			{ "wedge", "wedge", "wedge", "wedge", "wedge", "wedge", "wedge", "wedge" },
			{ "segmented", "long", "short", "big-headed", "big-tailed", "pincered", "broad", "tailed" },
			{ "hunter", "long", "brute", "fanged", "long-tailed", "forked", "snouted", "dart" },
			{ "ragged", "ragged", "ragged", "ragged", "ragged", "ragged", "ragged", "ragged" },
	};

	/** The name of a plan's variant, for the catalog. */
	public static String variantName(int form, int variant) {
		return VARIANT_NAMES[clamp(form, 0, VARIANT_NAMES.length - 1)][clamp(variant, 0, VARIANTS - 1)];
	}

	/** A form-agnostic action envelope: squash/stretch, offset, rotation, tint,
	 * dither-dissolve, plus a hovering glyph / expanding ring. */
	public static final class Mod {
		public double sA = 1, sP = 1, offA = 0, offP = 0, rot = 0, tintAmt = 0, dissolve = 0, ringT = -1;
		public int tint = 0xffffff, glyph = 0;
	}

	private static final Mod IDENTITY = new Mod();

	// ---- genome -> appearance ---------------------------------------------

	// Sprite cache: memoises draw() output keyed by a quantised descriptor so
	// identical (species, direction, animation frame, action, size) creatures are
	// rendered once and blitted thereafter. The cache is content-agnostic (see
	// SpriteCache); only phenoKey/drawCached below know what affects the pixels.
	private static final double TWO_PI = Math.PI * 2;
	/** Heading buckets and idle-animation frames a sprite is quantised into. Public
	 *  so an off-line baker (the web server's sprite atlas) lays cells out the same
	 *  way the live cache does, and the browser samples them by the same rule. */
	public static final int DIRS = 8, ANIM = 8;
	private static final int ACTF = 12, MAX_CACHE_RADIUS = 48;
	private static final SpriteCache CACHE = new SpriteCache(1536);

	/** Derives a stable, heritable phenotype from a genome (markers = identity). */
	public static Phenotype phenotype(Genome g) {
		Phenotype p = new Phenotype();
		p.color = snap(g.toColor().getRGB()); // 5 bits/channel: palette-ish, bounds the cache
		double m0 = clamp01(g.markers[0]), m1 = clamp01(g.markers[1]), m2 = clamp01(g.markers[2]);
		// What a creature EATS, and whether it flies, decide the body plan; the
		// markers only choose which variant of that plan it wears. Before this the
		// whole silhouette came from the markers, which are the mate-recognition
		// barcode -- so shape said which lineage a creature descended from and
		// nothing whatever about what it was. A hunter, a grazer and a scavenger
		// were drawn from one shape space: measured on a settled world, 349
		// creatures wore 95 shapes with no systematic difference between the roles.
		// You could not tell what you were looking at, which for an ecology being
		// watched is most of the point.
		// Which plan this clade wears is a property OF the clade, asked for rather
		// than re-decided here -- this used to be a fourth encoding of the same idea,
		// in a different order from the genome's, with nothing checking they agreed.
		int eco = g.clade.bodyPlan();
		// One body plan per trophic level, and these specifically: `form` is
		// not a free index -- 0 and 4 grow bilateral legs, 1 grows radial cilia, the
		// rest go bare -- so the plans are chosen for what they draw. A grazer walks
		// on plain legs, a hunter on the long-striding pair, a scavenger goes bare
		// and wears its feelers instead — and the parasite takes the radial-cilia
		// plan, the round clinging body of a thing that rides rather than walks.
		p.form = eco == 0 ? 0 : eco == 1 ? 3 : eco == 3 ? 1 : 4;
		// Flight shows in the limbs. A body that flies is not carrying a walking
		// undercarriage, so it keeps a single pair; a ground body's count is the
		// markers' business. Together with the lift and the flattened shadow the
		// draw already gives a flier, that is a silhouette rather than a hover.
		p.legs = g.flying ? 1 : 2 + (int) (m1 * 3);
		// The third marker picks which of the plan's eight bodies this lineage
		// wears. Quantised from ONE marker rather than hashed from all three, so a
		// mutation nudges a lineage across at most one boundary at a time and a
		// species keeps its body the way it keeps its leg count (marker 1) and its
		// mark (marker 0). This marker also feeds the blue of the colour, so body
		// and hue co-vary within a clade -- which is what a lineage looks like.
		p.variant = (int) (m2 * (VARIANTS - 0.001));
		p.core = eco; // repeated in the core mark: still legible when the body is small
		p.pattern = (int) (m0 * 2.999);
		// The two features that read fastest at a distance, spent on the two roles
		// that are not the default. Feelers for the scavenger, which finds its food
		// by smell rather than by looking at it; a tail for the hunter.
		p.antennae = eco == 1;
		p.tail = eco == 2;
		p.flying = g.flying;
		p.r = clamp((int) Math.round(g.size / 3.0), 2, 4);
		return p;
	}

	/** Maps an action + progress t in [0,1] to a generic modifier envelope. */
	public static Mod actionMod(int action, double t, int seed) {
		Mod m = new Mod();
		switch (action) {
		case A_LUNGE:
			if (t < 0.28) {
				double u = t / 0.28;
				m.sA = 1 - 0.2 * u;
				m.sP = 1 + 0.16 * u;
				m.offA = -1.0 * u;
			} else if (t < 0.5) {
				double u = (t - 0.28) / 0.22;
				m.sA = 0.8 + 0.75 * u;
				m.sP = 1.16 - 0.36 * u;
				m.offA = -1.0 + 3.0 * u;
				if (u > 0.75) {
					m.glyph = 1;
				}
			} else {
				double u = (t - 0.5) / 0.5;
				m.sA = 1.55 - 0.55 * u;
				m.sP = 0.8 + 0.2 * u;
				m.offA = 2.0 * (1 - u);
			}
			break;
		case A_HURT: {
			double dcy = Math.max(0, 1 - t * 1.5);
			m.tint = 0xff4030;
			m.tintAmt = 0.75 * dcy;
			m.offP = (hash((int) (t * 40), seed, 5) - 0.5) * 2.4 * dcy;
			m.offA = (hash((int) (t * 40) + 7, seed, 5) - 0.5) * 2.4 * dcy;
			m.sA = 1 - 0.14 * dcy;
			m.sP = 1 + 0.12 * dcy;
			break;
		}
		case A_EAT: {
			double c = Math.abs(Math.sin(t * Math.PI * 3));
			m.sA = 1 - 0.24 * c;
			m.sP = 1 + 0.16 * c;
			m.offA = 0.6 * Math.sin(t * Math.PI * 3);
			break;
		}
		case A_COURT: {
			double p = Math.sin(t * Math.PI * 2);
			m.sA = 1 + 0.13 * p;
			m.sP = 1 + 0.13 * p;
			m.tintAmt = 0.14 + 0.14 * Math.max(0, p);
			m.glyph = 3;
			break;
		}
		case A_ALARM: {
			double j = t < 0.55 ? 1 : Math.max(0, 1 - (t - 0.55) * 2.2);
			m.offP = (hash((int) (t * 60), seed, 6) - 0.5) * 2.4 * j;
			m.sP = 1 - 0.1 * j;
			m.sA = 1 + 0.14 * j;
			m.glyph = 2;
			break;
		}
		case A_DEATH:
			// A rot, not a throe. This used to spin three times and shrink to 5%,
			// which reads well over half a second and badly over the half-minute a
			// corpse now actually lasts: the body became a speck around t=0.6 and
			// was drawn as nothing for the rest of a span it spent lying there,
			// still solid, still worth its mass to a scavenger. What is drawn has
			// to match what exists.
			//
			// So the silhouette survives -- how big the animal was stays readable
			// until it is genuinely gone. It slumps a little (settling, not
			// vanishing), and the dissolve carries the decay on a quadratic so the
			// body holds together early and thins out at the end.
			m.rot = t * 0.15; // a slight settle, as if the body has given up its footing
			m.sA = 1 - 0.16 * t; // flattens as it goes
			m.sP = 1 - 0.10 * t;
			m.dissolve = t * t;
			break;
		case A_SPAWN:
			m.sA = Math.min(1, t * 1.25);
			m.sP = m.sA;
			m.dissolve = Math.max(0, 1 - t * 1.5);
			m.ringT = t;
			break;
		default:
			break;
		}
		return m;
	}

	// ---- drawing -----------------------------------------------------------

	/**
	 * Cached draw: renders through the {@link SpriteCache}. Direction, animation
	 * phase, action progress and on-screen size are quantised into a key; the
	 * sprite for a given key is drawn once (via {@link #draw}) into a small buffer
	 * and blitted thereafter. Falls back to a direct draw for very large sprites
	 * (rare, zoomed in) or when caching is disabled.
	 */
	public static void drawCached(Graphics2D g, int cx, int cy, double onScreenRadius,
			Phenotype ph, double heading, double phase, int action, double actionT) {
		if (!RenderFx.cacheSprites || onScreenRadius > MAX_CACHE_RADIUS || onScreenRadius < 1) {
			draw(g, cx, cy, onScreenRadius, ph, heading, phase,
					action == A_IDLE ? IDENTITY : actionMod(action, actionT, ph.color));
			return;
		}
		int sizeB = (int) Math.round(onScreenRadius);
		int dir = ((int) Math.round(heading / (TWO_PI / DIRS)) % DIRS + DIRS) % DIRS;
		int anim = action == A_IDLE ? (int) Math.floor(frac(phase / TWO_PI) * ANIM) : 0;
		int actF = action == A_IDLE ? 0 : clampI((int) (actionT * ACTF), 0, ACTF - 1);
		long key = phenoKey(ph);
		key = key * 131 + dir;
		key = key * 131 + anim;
		key = key * 131 + action;
		key = key * 131 + actF;
		key = key * 131 + sizeB;
		BufferedImage sp = CACHE.get(key, () -> renderSprite(ph, dir, anim, action, actF, sizeB));
		int artPx = Math.max(1, Math.round(sizeB / (float) ph.r));
		int center = (ph.r + spriteMargin(ph)) * artPx + artPx / 2;
		g.drawImage(sp, cx - center, cy - center, null);
	}

	/** Renders one sprite for a cache miss: reconstructs the draw params from the
	 * quantised key and calls {@link #draw} into a tight transparent buffer. */
	private static BufferedImage renderSprite(Phenotype ph, int dir, int anim, int action, int actF, int sizeB) {
		int artPx = Math.max(1, Math.round(sizeB / (float) ph.r));
		int half = ph.r + spriteMargin(ph); // art-px radius incl appendages / glyph / ring / shadow
		int dim = (2 * half + 1) * artPx;
		int center = half * artPx + artPx / 2;
		BufferedImage buf = new BufferedImage(dim, dim, BufferedImage.TYPE_INT_ARGB);
		Graphics2D bg = buf.createGraphics();
		double heading = dir * (TWO_PI / DIRS);
		double phase = (anim + 0.5) / ANIM * TWO_PI;
		Mod m = action == A_IDLE ? IDENTITY : actionMod(action, (actF + 0.5) / ACTF, ph.color);
		draw(bg, center, center, artPx * ph.r, ph, heading, phase, m);
		bg.dispose();
		return buf;
	}

	/**
	 * Bumped whenever the DRAWING changes for a phenotype that would otherwise key
	 * the same — a form's outline redefined, an appendage moved, a mark restyled.
	 *
	 * <p>A key is a promise that two things wearing it render identically, and the
	 * atlas endpoint cashes that promise in: it serves a baked sheet per key with a
	 * day of hard caching on the grounds that a key is immutable. That holds within
	 * a build and quietly stops holding across one. Redefining what form 4 draws,
	 * without this, would have left every viewer who had seen a hunter that week
	 * still looking at the old body — the shape fixed on the server and wrong in the
	 * browser, which is the most confusing way for a fix to fail.
	 */
	private static final int RENDER_VERSION = 3;

	/** Packs everything about a phenotype that affects the pixels into a key. Public
	 *  so the web layer can name a phenotype's atlas by the same stable id the live
	 *  sprite cache uses. Two genomes that render identically share a key. */
	public static long phenoKey(Phenotype ph) {
		long k = ph.color & 0xFFFFFFL;
		k = (k << 4) | RENDER_VERSION;
		k = (k << 3) | (ph.form & 7);
		k = (k << 3) | (ph.legs & 7);
		k = (k << 2) | (ph.core & 3);
		k = (k << 2) | (ph.pattern & 3);
		k = (k << 1) | (ph.antennae ? 1 : 0);
		k = (k << 1) | (ph.tail ? 1 : 0);
		k = (k << 1) | (ph.flying ? 1 : 0);
		k = (k << 3) | (ph.r & 7);
		k = (k << 3) | (ph.variant & 7);
		return k;
	}

	/**
	 * The SHAPE of a phenotype: {@link #phenoKey} minus the colour bits. Colour
	 * mutates almost continuously under evolution while the 16 bits of shape
	 * barely move within a lineage, so keying baked sprite atlases by colour
	 * meant a new 768px atlas for nearly every lineage variant — hundreds of
	 * sheets of essentially identical bodies. Every colour in an idle bake is
	 * {@code shade()} or {@code mixWhite()} of the base colour (or achromatic
	 * shadow), so one NEUTRAL atlas per shape (baked at {@link #NEUTRAL_COLOR})
	 * carries the full palette as a two-segment grey ramp the client re-tints
	 * per creature at draw time.
	 */
	public static long shapeKey(Phenotype ph) {
		long k = RENDER_VERSION;
		k = (k << 3) | (ph.form & 7);
		k = (k << 3) | (ph.legs & 7);
		k = (k << 2) | (ph.core & 3);
		k = (k << 2) | (ph.pattern & 3);
		k = (k << 1) | (ph.antennae ? 1 : 0);
		k = (k << 1) | (ph.tail ? 1 : 0);
		k = (k << 1) | (ph.flying ? 1 : 0);
		k = (k << 3) | (ph.r & 7);
		k = (k << 3) | (ph.variant & 7);
		return k;
	}

	/** The base colour neutral atlases bake at: exact mid-grey, so
	 *  {@code shade(c,f)} lands in 0..128 and {@code mixWhite(c,t)} in 128..255
	 *  and the client's ramp can invert both exactly (pivot 128). */
	public static final int NEUTRAL_COLOR = 0x808080;

	/** A copy of a phenotype with the colour neutralised for a shape bake. The
	 *  colour also seeds the form jitter, so same-shape creatures share their
	 *  jitter under this — the price of sharing the sheet. */
	public static Phenotype neutral(Phenotype ph) {
		Phenotype n = new Phenotype();
		n.color = NEUTRAL_COLOR;
		n.form = ph.form;
		n.legs = ph.legs;
		n.core = ph.core;
		n.pattern = ph.pattern;
		n.antennae = ph.antennae;
		n.tail = ph.tail;
		n.flying = ph.flying;
		n.r = ph.r;
		n.variant = ph.variant;
		return n;
	}

	/** Art-px margin around the body: room for appendages/glyph/ring, plus extra
	 * headroom below/above for a flyer's lifted body and its detached shadow. */
	private static int spriteMargin(Phenotype ph) {
		return ph.flying ? 11 : 7;
	}

	/** Cache stats for diagnostics. */
	public static String cacheStats() {
		return "sprites=" + CACHE.size() + " hits=" + CACHE.hits() + " misses=" + CACHE.misses();
	}

	private static int snap(int rgb) {
		return (snapCh((rgb >> 16) & 255) << 16) | (snapCh((rgb >> 8) & 255) << 8) | snapCh(rgb & 255);
	}

	private static int snapCh(int v) {
		return Math.min(255, (v & 0xF8) + 4); // 5 bits/channel, centred
	}

	private static double frac(double v) {
		return v - Math.floor(v);
	}

	private static int clampI(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	public static void draw(Graphics2D g, int cx, int cy, double onScreenRadius,
			Phenotype ph, double heading, double phase) {
		draw(g, cx, cy, onScreenRadius, ph, heading, phase, IDENTITY);
	}

	public static void draw(Graphics2D g, int cx, int cy, double onScreenRadius,
			Phenotype ph, double heading, double phase, Mod m) {
		int r = ph.r, seed = ph.color;
		int px = Math.max(1, (int) Math.round(onScreenRadius / r)); // screen px per art-px
		double h = heading + m.rot;
		double ux = Math.cos(h), uy = Math.sin(h), rx = -uy, ry = ux;
		// Airborne bodies are drawn lifted north of their ground point; the cast
		// shadow stays on the ground below, so the gap between them reads as height.
		int lift = ph.flying ? 5 : 0;
		int ox = cx + (int) Math.round((m.offA * ux + m.offP * rx) * px);
		int oy = cy + (int) Math.round((m.offA * uy + m.offP * ry) * px) - lift * px;
		double sA = m.sA, sP = m.sP;

		// Cast ground shadow at the contact point (light from screen-north), under
		// everything. A grounded body sits on a shadow whose footprint is a touch
		// larger than the body and nudged south, so a rim of it shows all round and
		// grows with the body; a flyer's shadow is displaced clear and a touch
		// smaller, so the detached shadow reads as height. Both offsets scale with
		// r, so the shadow tracks the entity's size instead of a fixed sliver.
		int shAlpha = (int) Math.round((ph.flying ? 82 : 125) * (1 - m.dissolve));
		if (shAlpha > 0) {
			int drop = ph.flying ? r + 2 : Math.max(1, (int) Math.round(r * 0.75));
			drawShadow(g, cx, cy + drop * px, px,
					r * (ph.flying ? 0.9 : 1.5), r * (ph.flying ? 0.45 : 0.95), shAlpha);
		}

		HashSet<Integer> body = new HashSet<Integer>();
		int rng = (int) ((r + 3) * Math.max(sA, sP)) + 1;
		for (double la = -rng; la <= rng; la += 0.5) {
			for (double pe = -rng; pe <= rng; pe += 0.5) {
				if (inForm(ph, la / sA, pe / sP, seed, phase)) {
					body.add(key(w0(la, pe, ux, rx), w1(la, pe, uy, ry)));
				}
			}
		}
		// Ambient shadow: faint halo just south of the silhouette.
		for (int p : body) {
			int dx = p / 128 - 32, dy = p % 128 - 32;
			if (!body.contains(key(dx, dy + 1))) {
				fill(g, ox, oy, px, dx, dy + 1, 0, 0, 0, (int) (56 * (1 - m.dissolve)));
			}
		}
		// Appendages, in the dark shade every limb wears.
		int limb = shade(ph.color, 0.5);
		if (ph.form == 0 || ph.form == 4) { // bilateral legs, along the body's flanks
			int pairs = ph.legs, v = ph.variant;
			double[] ext = extent(ph); // {back, front, half-width}
			// Where a leg roots: a grazer's at its flank, a hunter's tucked under a
			// body that is widest at the shoulder (a narrow hunter tucks them in
			// further, a brute less). And how far along the body they may sit.
			double half = (ph.form == 4
					? Math.max(0.5, r - (v == 2 ? 0.5 : (v == 1 || v == 7) ? 1.3 : 1.0))
					: ext[2] + 0.1) * sP;
			double len = (ph.form == 4 ? 2.4 : v == 2 ? 0.9 : 1.4) * sP;
			double inset = ph.form == 4 ? 0.9 : 1.4;
			double la0 = -(ext[0] - inset) * sA, la1 = (ext[1] - inset) * sA;
			for (int i = 0; i < pairs; i++) {
				double la = pairs == 1 ? (la0 + la1) / 2 : lerp(la0, la1, i / (double) (pairs - 1));
				double drive = 0.55 + 0.45 * Math.sin(phase + i * 2.1);
				for (double e = half; e <= half + len * drive; e += 0.7) {
					stampMod(g, ox, oy, px, w0(la, e, ux, rx), w1(la, e, uy, ry), limb, m, seed);
					stampMod(g, ox, oy, px, w0(la, -e, ux, rx), w1(la, -e, uy, ry), limb, m, seed);
				}
			}
		} else if (ph.form == 1 && ph.variant == 2) { // four spikes, and no ring
			for (int i = 0; i < 4; i++) {
				double a = Math.PI / 4 + i * Math.PI / 2;
				for (double d = r + 0.8; d <= r + 1.7; d += 0.8) {
					double la = Math.cos(a) * d * sA, pe = Math.sin(a) * d * sP;
					stampMod(g, ox, oy, px, w0(la, pe, ux, rx), w1(la, pe, uy, ry), limb, m, seed);
				}
			}
		} else if (ph.form == 1 && ph.variant != 1) { // radial cilia, around whatever the body is
			int n = ph.legs * 2 + 5;
			double[] ring = ciliaRing(ph); // {centre along, radius along, radius across}
			double wob = 0.35 * Math.sin(phase * 1.5);
			int rings = ph.variant == 6 ? 2 : 1; // haloed: the cilia are two deep
			for (int k = 0; k < rings; k++) {
				double reach = 0.8 + k * 0.8 + wob;
				for (int i = 0; i < n; i++) {
					double a = 2 * Math.PI * i / n + phase * 0.25;
					double la = (ring[0] + Math.cos(a) * (ring[1] + reach)) * sA;
					double pe = Math.sin(a) * (ring[2] + reach) * sP;
					stampMod(g, ox, oy, px, w0(la, pe, ux, rx), w1(la, pe, uy, ry), limb, m, seed);
				}
			}
		}
		// Body, lit from screen-north.
		for (int p : body) {
			int dx = p / 128 - 32, dy = p % 128 - 32;
			double ts = (dy + r) / (2.0 * r);
			int col = ts < 0.4 ? mixWhite(ph.color, 0.4) : (ts > 0.72 ? shade(ph.color, 0.6) : ph.color);
			stampMod(g, ox, oy, px, dx, dy, col, m, seed);
		}
		if (ph.core > 0) {
			stampMod(g, ox, oy, px, 0, 0, ph.core == 1 ? shade(ph.color, 0.5) : mixWhite(ph.color, 0.55), m, seed);
		}
		if (ph.pattern == 1) {
			for (double la = -r; la <= r; la += 0.5) {
				int dx = w0(la * sA, 0, ux, rx), dy = w1(la * sA, 0, uy, ry);
				if (body.contains(key(dx, dy))) {
					stampMod(g, ox, oy, px, dx, dy, shade(ph.color, 0.72), m, seed);
				}
			}
		} else if (ph.pattern == 2) {
			int dx = w0((r - 0.3) * sA, 0, ux, rx), dy = w1((r - 0.3) * sA, 0, uy, ry);
			if (body.contains(key(dx, dy))) {
				stampMod(g, ox, oy, px, dx, dy, mixWhite(ph.color, 0.55), m, seed);
			}
		}
		if (ph.antennae) {
			// Just beyond the front of THIS body. Pinned to r + 0.9 they landed
			// inside a long body and vanished — the scavenger's one unmistakable
			// feature, swallowed by the shape meant to carry it.
			double nose = (extent(ph)[1] + 0.9) * sA;
			stampMod(g, ox, oy, px, w0(nose, 0.9 * sP, ux, rx), w1(nose, 0.9 * sP, uy, ry),
					shade(ph.color, 0.55), m, seed);
			stampMod(g, ox, oy, px, w0(nose, -0.9 * sP, ux, rx), w1(nose, -0.9 * sP, uy, ry),
					shade(ph.color, 0.55), m, seed);
		}
		if (ph.tail) {
			// Just behind THIS body, for the same reason the feelers sit just ahead
			// of it. The hunter's tail variants are tails, so they are drawn here in
			// the tail's shade rather than as outline: a broad paddle two long, or
			// forked. The paddle was a plain two-pixel tail first, and on the
			// silhouette count that differed from the hunter by ONE pixel.
			double back = extent(ph)[0];
			int tint = shade(ph.color, 0.6);
			double[][] tail = ph.variant == 5
					? new double[][] { { back + 0.9, 0.8 }, { back + 0.9, -0.8 }, { back + 1.6, 1.3 }, { back + 1.6, -1.3 } }
					: ph.variant == 4 ? new double[][] { { back + 0.9, 0 }, { back + 0.9, 0.7 }, { back + 0.9, -0.7 },
							{ back + 1.7, 0 } }
					: new double[][] { { back + 0.9, 0 } };
			for (double[] t : tail) {
				double la = -t[0] * sA, pe = t[1] * sP;
				stampMod(g, ox, oy, px, w0(la, pe, ux, rx), w1(la, pe, uy, ry), tint, m, seed);
			}
		}
		if (m.ringT >= 0) {
			drawRing(g, cx, cy, px, m.ringT * (r + 5), 1 - m.ringT);
		}
		if (m.glyph > 0) {
			drawGlyph(g, cx, cy - (r + 3) * px, px, m.glyph);
		}
	}

	// ---- silhouette --------------------------------------------------------
	//
	// Every worn plan has eight bodies (VARIANT_NAMES), and each is described
	// here twice: inForm() says which body-local art-pixels are body, and
	// extent() says how far that body reaches, which is where its legs, feelers
	// and tail hang from. The two have to agree. A feature pinned to `r` instead
	// of to the body's own edge lands inside a long body and vanishes -- the
	// scavenger's feelers already did that once.
	//
	// Reach is budgeted. The web atlas draws every body into a 96px cell at a
	// fixed art radius, so the farthest art-pixel any variant may claim is set by
	// the SMALLEST body: at r = 2 it is four art-pixels from the centre, at r = 3
	// seven, at r = 4 eleven. Nothing here reaches past r + 2 -- a long body is
	// r + 1.9, a two-pixel tail ends at r + 1.7 -- and EveryBodyStaysInItsCell
	// pins it, because a body that crosses its cell edge is drawn into the next
	// frame of the sheet.

	private static final double[][] NONE = {};

	/** A body's reach in art-px, {back, front, half-width}: where its outline
	 *  ends behind, ahead, and across. */
	private static double[] extent(Phenotype ph) {
		int r = ph.r, v = ph.variant;
		switch (ph.form) {
		case 1:
			switch (v) {
			case 1: return new double[] { r + 0.5, r + 0.5, r + 0.5 };
			case 3: return new double[] { r + 0.9, r + 0.9, Math.max(0.9, r - 0.5) };
			case 5: return new double[] { r * 1.30, r * 1.17, r * 0.85 };
			case 7: return new double[] { Math.max(0.9, r - 0.5), Math.max(0.9, r - 0.5), r + 0.9 };
			default: {
				double rr = Math.sqrt(r * r + r * 0.5);
				return new double[] { rr, rr, rr };
			}
			}
		case 2:
			return new double[] { r + 0.3, r + 0.3, r + 0.4 };
		case 3: {
			double[][] s = segments(ph);
			double[] c = s[0], rad = s[1];
			double w = 0;
			for (double x : rad) {
				w = Math.max(w, x * (v == 6 ? 1.4 : 1));
			}
			return new double[] { rad[0] - c[0], c[c.length - 1] + rad[rad.length - 1], w };
		}
		case 4:
			switch (v) {
			case 1: return new double[] { r + 1.4, r + 1.0, (2 * r + 2.1) * 0.38 + 0.3 };
			case 2: return new double[] { r + 0.3, r + 0.3, 2 * r * 0.7 + 0.6 };
			case 7: return new double[] { r + 0.8, r + 0.6, (2 * r + 1.1) * 0.32 + 0.3 };
			default: return new double[] { r + 0.3, r + 0.3, 2 * r * 0.5 + 0.4 };
			}
		case 5:
			return new double[] { r + 0.6, r + 0.6, r + 0.6 };
		default:
			switch (v) {
			case 1: return new double[] { r + 1.9, r + 1.9, Math.max(0.7, r - 0.7) };
			case 2: return new double[] { r + 0.3, r + 0.3, r + 0.3 };
			case 4: return new double[] { Math.max(0.9, r - 0.4), Math.max(0.9, r - 0.4), r + 0.7 };
			case 7: return new double[] { r * 1.34, r * 1.28, r * 0.72 };
			default: return new double[] { r + 0.8, r + 0.8, Math.max(0.7, r - 0.3) };
			}
		}
	}

	/** The scavenger's segments as {centres along, radii}, rear first. Drawn a
	 *  little smaller than the body was born: at full size the three-segment
	 *  form out-massed the grazer it stands next to, and a scavenger that dwarfs
	 *  what it feeds on reads wrong. */
	private static double[][] segments(Phenotype ph) {
		int r = ph.r;
		double d = Math.max(1.2, r * 0.8), r2 = Math.max(1, r - 1.2), small = Math.max(1, r2 - 0.2);
		switch (ph.variant) {
		case 1: { // long: four
			double d4 = Math.max(1.1, r * 0.7), r4 = Math.max(1, r - 1.4);
			return new double[][] { { -1.5 * d4, -0.5 * d4, 0.5 * d4, 1.5 * d4 }, { r4, r4, r4, r4 } };
		}
		case 2: { // short: two, each bigger
			double r3 = Math.max(1.2, r - 0.7), d3 = r3 * 0.75;
			return new double[][] { { -d3, d3 }, { r3, r3 } };
		}
		case 3: // the head carries the mass
			return new double[][] { { -d, 0, d }, { small, small, r2 + 0.7 } };
		case 4: // the tail does
			return new double[][] { { -d, 0, d }, { r2 + 0.7, small, small } };
		default:
			return new double[][] { { -d, 0, d }, { r2, r2, r2 } };
		}
	}

	/** Where a parasite's cilia ring sits: {centre along, radius along, radius
	 *  across}, so the fringe follows an oval body and the twin's rear lobe
	 *  rather than a circle the body no longer is. */
	private static double[] ciliaRing(Phenotype ph) {
		int r = ph.r;
		switch (ph.variant) {
		case 3: return new double[] { 0, r + 0.9, Math.max(0.9, r - 0.5) };
		case 5: return new double[] { -r * 0.45, r * 0.85, r * 0.85 };
		case 7: return new double[] { 0, Math.max(0.9, r - 0.5), r + 0.9 };
		default: return new double[] { 0, r, r };
		}
	}

	/**
	 * A variant's fixed features as small discs in body-local art-px {along,
	 * across, radius}: the horns, hooks, pincers, frill and stubs that are the
	 * difference between two bodies of the same outline. They ARE outline --
	 * {@link #inForm} unions them into the body, so they take its light and cast
	 * its shadow -- and they hang off the body's own extent, never off {@code r}.
	 *
	 * <p>Discs rather than pixels, and sized to {@code r}: as single limb-dark
	 * pixels they vanished into the ambient shadow at every size on the
	 * comparison sheet. A feature has to thicken as the body does or a horn on a
	 * big body is a speck. {@code t} is how far a feature may stand off, which
	 * is nothing at r = 2 -- the smallest body has one art-pixel of reach past
	 * its outline before it crosses its atlas cell -- so every centre + radius
	 * here stays under r + 2.4.
	 */
	private static double[][] blobs(Phenotype ph) {
		int r = ph.r, v = ph.variant;
		double[] e = extent(ph);
		double b = e[0], f = e[1], w = e[2];
		double k = 0.35 + 0.15 * r; // a feature's radius, 0.65 .. 0.95
		double t = (r - 2) * 0.45;  // its standoff, 0 .. 0.9
		switch (ph.form) {
		case 0:
			switch (v) {
			case 3: // horns: two prongs, splayed outward as they go
				return new double[][] { { f + 0.2, r * 0.45, k }, { f + 0.2, -r * 0.45, k },
						{ f + 0.9 + t, r * 0.45 + 0.5 + t * 0.5, k }, { f + 0.9 + t, -(r * 0.45 + 0.5 + t * 0.5), k } };
			case 5: // a stub tail
				return new double[][] { { -(b + 0.4), 0, k }, { -(b + 1.0 + t * 0.4), 0, k } };
			case 6: // a frill: a bar across the nose
				return new double[][] { { f + 0.5 + t * 0.3, 0, k },
						{ f + 0.4 + t * 0.3, 0.9 + t * 0.5, k }, { f + 0.4 + t * 0.3, -(0.9 + t * 0.5), k } };
			default:
				return NONE;
			}
		case 1:
			return v == 4 // hooks: two, curling outward
					? new double[][] { { f + 0.3, 0.9, k }, { f + 0.3, -0.9, k },
							{ f + 0.9 + t * 0.4, 1.3 + t * 0.4, k }, { f + 0.9 + t * 0.4, -(1.3 + t * 0.4), k } }
					: NONE;
		case 3:
			switch (v) {
			case 5: // pincers, outside the feelers. Their tips stand off with t: at
				// r = 2 a tip any further out crossed the cell on the diagonal
				// headings, where a corner's reach is the radial distance.
				return new double[][] { { f + 0.4, w + 0.5, k }, { f + 0.4, -(w + 0.5), k },
						{ f + 0.7 + t * 0.6, w + 0.6 + t * 0.6, k }, { f + 0.7 + t * 0.6, -(w + 0.6 + t * 0.6), k } };
			case 7: // a tail behind the last segment
				return new double[][] { { -(b + 0.4), 0, k }, { -(b + 1.0 + t * 0.4), 0, k } };
			default:
				return NONE;
			}
		case 4:
			switch (v) {
			case 3: // fangs
				return new double[][] { { f + 0.5, 0.9, k }, { f + 0.5, -0.9, k },
						{ f + 1.1 + t * 0.4, 1.3 + t * 0.4, k }, { f + 1.1 + t * 0.4, -(1.3 + t * 0.4), k } };
			case 6: // a snout
				return new double[][] { { f + 0.5, 0, k }, { f + 1.2 + t * 0.4, 0, k } };
			default: // the tail variants (4, 5) are tails, drawn with the tail
				return NONE;
			}
		default:
			return NONE;
		}
	}

	private static boolean inBlob(Phenotype ph, double la, double pe) {
		for (double[] d : blobs(ph)) {
			if (disc(la, pe, d[0], d[1], d[2])) {
				return true;
			}
		}
		return false;
	}

	private static boolean ellipse(double la, double pe, double a, double b) {
		return sq(la / a) + sq(pe / b) <= 1.05;
	}

	private static boolean disc(double la, double pe, double cx, double cy, double rad) {
		return sq(la - cx) + sq(pe - cy) <= rad * rad;
	}

	/** Broad at the front and tapering to the back: the hunter's outline. */
	private static boolean wedge(double la, double pe, double back, double front, double taper, double tip) {
		return la >= -back && la <= front && Math.abs(pe) <= (back - 0.3 + la) * taper + tip;
	}

	private static boolean inForm(Phenotype ph, double la, double pe, int seed, double phase) {
		return inBody(ph, la, pe, seed, phase) || inBlob(ph, la, pe);
	}

	private static boolean inBody(Phenotype ph, double la, double pe, int seed, double phase) {
		int r = ph.r, v = ph.variant;
		switch (ph.form) {
		case 1: // the parasite: round, and its variants are what round can become
			switch (v) {
			case 1: return disc(la, pe, 0, 0, r + 0.5);
			case 3: return ellipse(la, pe, r + 0.9, Math.max(0.9, r - 0.5));
			case 5: return disc(la, pe, r * 0.55, 0, r * 0.62) || disc(la, pe, -r * 0.45, 0, r * 0.85);
			case 7: return ellipse(la, pe, Math.max(0.9, r - 0.5), r + 0.9);
			default: return la * la + pe * pe <= r * r + r * 0.5;
			}
		case 2:
			return la <= r + 0.3 && la >= -r - 0.3 && Math.abs(pe) <= (r - la) * 0.5 + 0.4;
		case 3: { // segments that undulate on the gait clock, however many there are
			double[][] s = segments(ph);
			double[] c = s[0], rad = s[1];
			double along = v == 6 ? 0.85 : 1, across = v == 6 ? 1.4 : 1, mid = (c.length - 1) / 2.0;
			for (int i = 0; i < c.length; i++) {
				double o = 0.8 * Math.sin(phase + (mid - i) * 1.3);
				if (sq((la - c[i]) / (rad[i] * along)) + sq((pe - o) / (rad[i] * across)) <= 1) {
					return true;
				}
			}
			return false;
		}
		case 4:
			// The hunter: broad across the shoulders and tapering to the back, the
			// mirror of the scavenger's forward-pointing wedge and nothing like the
			// grazer's ellipse. It used to be a circle a shade smaller than form 0,
			// which meant a predator and a grazer differed only in leg length and a
			// one-pixel tail -- true at the size a sprite sheet is baked, invisible
			// at the size a creature is actually watched. Outline is the only thing
			// that survives being drawn small, so the roles differ in outline -- and
			// so do the variants: longer, wider, narrower, never just marked.
			switch (v) {
			case 1: return wedge(la, pe, r + 1.4, r + 1.0, 0.38, 0.3);
			case 2: return wedge(la, pe, r + 0.3, r + 0.3, 0.7, 0.6);
			case 7: return wedge(la, pe, r + 0.8, r + 0.6, 0.32, 0.3);
			default: return wedge(la, pe, r + 0.3, r + 0.3, 0.5, 0.4);
			}
		case 5:
			int q = (int) Math.floor((Math.atan2(pe, la) + Math.PI) / (2 * Math.PI) * 6);
			double pr = r + 0.6 * (hash(q, seed, 4) - 0.5) * 2 + 0.5 * Math.sin(phase + q);
			return la * la + pe * pe <= pr * pr;
		default: // the grazer's ellipse, and what an ellipse can become
			switch (v) {
			case 1: return ellipse(la, pe, r + 1.9, Math.max(0.7, r - 0.7));
			case 2: return ellipse(la, pe, r + 0.3, r + 0.3);
			case 4: return ellipse(la, pe, Math.max(0.9, r - 0.4), r + 0.7);
			case 7: return disc(la, pe, r * 0.62, 0, r * 0.66) || disc(la, pe, -r * 0.62, 0, r * 0.72);
			default: return ellipse(la, pe, r + 0.8, Math.max(0.7, r - 0.3));
			}
		}
	}

	// ---- pixel plumbing ----------------------------------------------------

	private static int w0(double la, double pe, double ux, double rx) {
		return (int) Math.round(la * ux + pe * rx);
	}

	private static int w1(double la, double pe, double uy, double ry) {
		return (int) Math.round(la * uy + pe * ry);
	}

	private static int key(int dx, int dy) {
		return (dx + 32) * 128 + (dy + 32);
	}

	private static void stampMod(Graphics2D g, int ox, int oy, int px, int dx, int dy, int rgb, Mod m, int seed) {
		if (m.dissolve > 0 && hash(dx + 40, dy + 40, seed) < m.dissolve) {
			return;
		}
		int c = m.tintAmt > 0 ? mix(rgb, m.tint, Math.min(1, m.tintAmt)) : rgb;
		fill(g, ox, oy, px, dx, dy, (c >> 16) & 255, (c >> 8) & 255, c & 255, 255);
	}

	private static void fill(Graphics2D g, int ox, int oy, int px, int dx, int dy, int r, int gg, int b, int a) {
		g.setColor(new Color(r, gg, b, a));
		g.fillRect(ox + dx * px - px / 2, oy + dy * px - px / 2, px, px);
	}

	private static void drawGlyph(Graphics2D g, int gx, int gy, int px, int type) {
		int c;
		switch (type) {
		case 1:
			c = 0xfff2b0;
			g.setColor(new Color(c));
			for (int[] o : new int[][] { { 0, 0 }, { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } }) {
				g.fillRect(gx + o[0] * px - px / 2, gy + o[1] * px - px / 2, px, px);
			}
			break;
		case 2:
			c = 0xffe14a;
			g.setColor(new Color(c));
			for (int[] o : new int[][] { { 0, -2 }, { 0, -1 }, { 0, 1 } }) {
				g.fillRect(gx + o[0] * px - px / 2, gy + o[1] * px - px / 2, px, px);
			}
			break;
		case 3:
			c = 0xff6ab0;
			g.setColor(new Color(c));
			for (int[] o : new int[][] { { -1, -1 }, { 1, -1 }, { -1, 0 }, { 0, 0 }, { 1, 0 }, { 0, 1 } }) {
				g.fillRect(gx + o[0] * px - px / 2, gy + o[1] * px - px / 2, px, px);
			}
			break;
		default:
			break;
		}
	}

	/** A soft dark ground ellipse: solid core plus a half-alpha penumbra ring. */
	private static void drawShadow(Graphics2D g, int cx, int cy, int px, double rx, double ry, int alpha) {
		int rr = (int) Math.ceil(Math.max(rx, ry));
		for (int dy = -rr; dy <= rr; dy++) {
			for (int dx = -rr; dx <= rr; dx++) {
				double e = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry);
				if (e > 1.0) {
					continue;
				}
				g.setColor(new Color(0, 0, 0, e < 0.55 ? alpha : alpha / 2));
				g.fillRect(cx + dx * px - px / 2, cy + dy * px - px / 2, px, px);
			}
		}
	}

	private static void drawRing(Graphics2D g, int cx, int cy, int px, double radius, double alpha) {
		int n = Math.max(8, (int) (radius * 4));
		g.setColor(new Color(0xbf, 0xe0, 0xff, (int) (140 * Math.max(0, alpha))));
		for (int i = 0; i < n; i++) {
			double a = 2 * Math.PI * i / n;
			int dx = (int) Math.round(Math.cos(a) * radius), dy = (int) Math.round(Math.sin(a) * radius);
			g.fillRect(cx + dx * px - px / 2, cy + dy * px - px / 2, px, px);
		}
	}

	// ---- small math --------------------------------------------------------

	private static double sq(double v) {
		return v * v;
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static double clamp01(double v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}

	private static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static int shade(int rgb, double f) {
		return (cl((int) (((rgb >> 16) & 255) * f)) << 16) | (cl((int) (((rgb >> 8) & 255) * f)) << 8)
				| cl((int) ((rgb & 255) * f));
	}

	private static int mixWhite(int rgb, double t) {
		return mix(rgb, 0xffffff, t);
	}

	private static int mix(int a, int b, double t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	private static int cl(int v) {
		return v < 0 ? 0 : (v > 255 ? 255 : v);
	}

	private static double hash(int x, int y, int s) {
		int h = x * 374761393 + y * 668265263 + s * 2147483647;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= h >>> 16;
		return (h & 0x7fffffff) / (double) 0x7fffffff;
	}
}
