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

	/** Heritable appearance: silhouette form + features + size, plus colour. */
	public static final class Phenotype {
		public int color, form, legs, core, pattern, r;
		public boolean antennae, tail;
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
	private static final int DIRS = 8, ANIM = 8, ACTF = 12, MAX_CACHE_RADIUS = 48;
	private static final SpriteCache CACHE = new SpriteCache(1536);

	/** Derives a stable, heritable phenotype from a genome (markers = identity). */
	public static Phenotype phenotype(Genome g) {
		Phenotype p = new Phenotype();
		p.color = snap(g.toColor().getRGB()); // 5 bits/channel: palette-ish, bounds the cache
		double m0 = clamp01(g.markers[0]), m1 = clamp01(g.markers[1]), m2 = clamp01(g.markers[2]);
		p.form = (int) (m0 * 5.999);
		p.legs = 2 + (int) (m1 * 3);
		p.core = (int) (m2 * 3);
		p.pattern = (int) (((m0 + m1) * 0.5) * 2.999);
		p.antennae = m1 > 0.45;
		p.tail = m2 > 0.5;
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
			m.rot = t * Math.PI * 3;
			m.sA = Math.max(0.05, 1 - t);
			m.sP = Math.max(0.05, 1 - t);
			m.dissolve = t;
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
		int center = (ph.r + 7) * artPx + artPx / 2;
		g.drawImage(sp, cx - center, cy - center, null);
	}

	/** Renders one sprite for a cache miss: reconstructs the draw params from the
	 * quantised key and calls {@link #draw} into a tight transparent buffer. */
	private static BufferedImage renderSprite(Phenotype ph, int dir, int anim, int action, int actF, int sizeB) {
		int artPx = Math.max(1, Math.round(sizeB / (float) ph.r));
		int half = ph.r + 7; // art-px radius incl appendages / glyph / ring / action offset
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

	/** Packs everything about a phenotype that affects the pixels into a key. */
	private static long phenoKey(Phenotype ph) {
		long k = ph.color & 0xFFFFFFL;
		k = (k << 3) | (ph.form & 7);
		k = (k << 3) | (ph.legs & 7);
		k = (k << 2) | (ph.core & 3);
		k = (k << 2) | (ph.pattern & 3);
		k = (k << 1) | (ph.antennae ? 1 : 0);
		k = (k << 1) | (ph.tail ? 1 : 0);
		k = (k << 3) | (ph.r & 7);
		return k;
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
		int ox = cx + (int) Math.round((m.offA * ux + m.offP * rx) * px);
		int oy = cy + (int) Math.round((m.offA * uy + m.offP * ry) * px);
		double sA = m.sA, sP = m.sP;

		HashSet<Integer> body = new HashSet<Integer>();
		int rng = (int) ((r + 2) * Math.max(sA, sP)) + 1;
		for (double la = -rng; la <= rng; la += 0.5) {
			for (double pe = -rng; pe <= rng; pe += 0.5) {
				if (inForm(ph.form, la / sA, pe / sP, r, seed, phase)) {
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
		// Appendages.
		if (ph.form == 0 || ph.form == 4) { // bilateral legs
			int pairs = ph.legs;
			double half = (ph.form == 4 ? r - 1 : r - 0.2) * sP, len = (ph.form == 4 ? 2.4 : 1.4) * sP;
			for (int i = 0; i < pairs; i++) {
				double la = (pairs == 1 ? 0 : lerp(-r + 0.6, r - 0.6, i / (double) (pairs - 1))) * sA;
				double drive = 0.55 + 0.45 * Math.sin(phase + i * 2.1);
				for (double e = half; e <= half + len * drive; e += 0.7) {
					stampMod(g, ox, oy, px, w0(la, e, ux, rx), w1(la, e, uy, ry), shade(ph.color, 0.5), m, seed);
					stampMod(g, ox, oy, px, w0(la, -e, ux, rx), w1(la, -e, uy, ry), shade(ph.color, 0.5), m, seed);
				}
			}
		} else if (ph.form == 1) { // radial cilia
			int n = ph.legs * 2 + 5;
			double base = (r + 0.8) * ((sA + sP) / 2) + 0.35 * Math.sin(phase * 1.5);
			for (int i = 0; i < n; i++) {
				double a = 2 * Math.PI * i / n + phase * 0.25, la = Math.cos(a) * base, pe = Math.sin(a) * base;
				stampMod(g, ox, oy, px, w0(la, pe, ux, rx), w1(la, pe, uy, ry), shade(ph.color, 0.5), m, seed);
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
			stampMod(g, ox, oy, px, w0((r + 0.9) * sA, 0.9 * sP, ux, rx), w1((r + 0.9) * sA, 0.9 * sP, uy, ry),
					shade(ph.color, 0.55), m, seed);
			stampMod(g, ox, oy, px, w0((r + 0.9) * sA, -0.9 * sP, ux, rx), w1((r + 0.9) * sA, -0.9 * sP, uy, ry),
					shade(ph.color, 0.55), m, seed);
		}
		if (ph.tail) {
			stampMod(g, ox, oy, px, w0(-(r + 0.9) * sA, 0, ux, rx), w1(-(r + 0.9) * sA, 0, uy, ry),
					shade(ph.color, 0.6), m, seed);
		}
		if (m.ringT >= 0) {
			drawRing(g, cx, cy, px, m.ringT * (r + 5), 1 - m.ringT);
		}
		if (m.glyph > 0) {
			drawGlyph(g, cx, cy - (r + 3) * px, px, m.glyph);
		}
	}

	// ---- silhouette --------------------------------------------------------

	private static boolean inForm(int form, double la, double pe, int r, int seed, double phase) {
		switch (form) {
		case 1:
			return la * la + pe * pe <= r * r + r * 0.5;
		case 2:
			return la <= r + 0.3 && la >= -r - 0.3 && Math.abs(pe) <= (r - la) * 0.5 + 0.4;
		case 3:
			double d = Math.max(1.2, r), r2 = Math.max(1, r - 0.6), amp = 0.8;
			double o0 = amp * Math.sin(phase + 1.3), o1 = amp * Math.sin(phase), o2 = amp * Math.sin(phase - 1.3);
			return Math.min(sq(la - d) + sq(pe - o2),
					Math.min(sq(la) + sq(pe - o1), sq(la + d) + sq(pe - o0))) <= r2 * r2;
		case 4:
			return la * la + pe * pe <= sq(r - 1) + (r - 1) * 0.5;
		case 5:
			int q = (int) Math.floor((Math.atan2(pe, la) + Math.PI) / (2 * Math.PI) * 6);
			double pr = r + 0.6 * (hash(q, seed, 4) - 0.5) * 2 + 0.5 * Math.sin(phase + q);
			return la * la + pe * pe <= pr * pr;
		default:
			return sq(la / (r + 0.8)) + sq(pe / Math.max(0.7, r - 0.3)) <= 1.05;
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
