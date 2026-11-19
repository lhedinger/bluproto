package net.hedinger.prototype.engine;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.HashMap;
import java.util.Map;

/**
 * The creatures as specimens: authored bodies drawn at their TRUE size on the
 * world's own art-pixel lattice -- the lattice the ground and the large flora
 * are drawn on, one art-pixel a twelfth of a tile. ART-STYLE §5, "specimens".
 *
 * <p>A body is 1 to 7 art-pixels across, because that is how big the bodies
 * are: a radius of 0.02 to 0.27 tile. At that size a shrunk drawing is a speck,
 * so each form is drawn three times, at three size classes ({@link #sizeClass}),
 * and every class keeps the one mark that names the form -- the sponge's pore,
 * the diatom's pores, the lancet's keel, the crawler's legs, the seed's thread.
 * They read as organisms of this world, plankton rather than animals: no faces,
 * no heads, no icons; the heading is carried by the mass.
 *
 * <p>Which form a creature wears follows its plan ({@link ProcCreature.Phenotype#form},
 * set by what it eats) and its variant (the third marker), as the old bodies did:
 * grazers are amoebae and diatoms, hunters crawlers and lancets, scavengers
 * clusters, sponges, shards and brushes, parasites splinters and seeds. A
 * variant past the plan's forms wears the same form mirrored across its axis.
 *
 * <p>The light is the large flora's (direction C): each part lit on its own --
 * a bright crest along its north edge, the body, a sunk underside -- and where
 * a later part lies against an earlier one, the earlier takes the crevice. No
 * outline. Every tone is a {@code shade()} or {@code mixWhite()} of the body
 * colour, so a bake at {@link ProcCreature#NEUTRAL_COLOR} is a grey ramp the
 * client re-tints per creature, exactly as the old atlases were.
 *
 * <p>Motion is one art-pixel at a time ({@link #GAIT}): legs step in antiphase,
 * a thread sways, bristles sweep, pores shimmer, a bead jostles, an amoeba
 * changes its outline. Rotation is by quarter turns of the silhouette, lossless
 * on the lattice, with the light applied after; a diagonal heading wears the
 * nearest cardinal, since a 45-degree turn of a five-pixel body is the lumpy
 * rasterisation §5 forbids.
 */
public final class Specimen {

	/** Size classes: small, medium, large. */
	public static final int SMALL = 0, MEDIUM = 1, LARGE = 2, CLASSES = 3;
	/** Atlas pixels to one world art-pixel. The client draws a cell at exactly the world's art-pixel size. */
	public static final int ART = 6;
	/** A cell is this many art-pixels square: room for the largest body, its shadow and a flyer's lift. */
	public static final int CELL_ART = 16;
	/** Gait frames, the atlas rows. */
	public static final int FRAMES = 8;

	private Specimen() {
	}

	/** One authored part: its rows, where it sits (east-facing), and how it moves. */
	static final class Part {
		final String[] st;
		final int x, y;
		final String tag;

		Part(String[] st, int x, int y, String tag) {
			this.st = st;
			this.x = x;
			this.y = y;
			this.tag = tag;
		}
	}

	private static Part p(String[] st, int x, int y) {
		return new Part(st, x, y, "");
	}

	private static Part p(String[] st, int x, int y, String tag) {
		return new Part(st, x, y, tag);
	}

	private static String[] r(String... rows) {
		return rows;
	}

	/** Form name -> its three size classes' parts. Marks: '#' tissue, 'p' a pale bristle,
	 *  'l' a dark leg or thread, 'e' a dark mark (nucleus, pore, keel). The table is
	 *  shared verbatim with the exploration sheets it was drawn on. */
	static final Map<String, Part[][]> FORMS = new HashMap<String, Part[][]>();

	static {
		// grazers
		FORMS.put("amoeba", new Part[][] {
				{ p(r("##", "#e"), 0, 0) },
				{ p(r(".##.", "#e##", "###."), 0, 0, "popA"), p(r(".##.", "#e##", ".###"), 0, 0, "popB") },
				{ p(r(".###.", "##e##", "####.", "#..##"), 0, 0, "popA"), p(r(".###.", "##e##", ".####", "##..#"), 0, 0, "popB") } });
		FORMS.put("diatom", new Part[][] {
				{ p(r("###"), 0, 0) },
				{ p(r(".###.", "#####", ".###."), 0, 0), p(r("e.e"), 1, 1, "pore") },
				{ p(r(".#####.", "#######", "#######", ".#####."), 0, 0), p(r("e.e.e"), 1, 1, "pore"), p(r(".e.e"), 1, 2, "pore") } });
		// hunters
		FORMS.put("crawler", new Part[][] {
				{ p(r("l.l"), 0, 0, "legN"), p(r("###"), 0, 1), p(r("l.l"), 0, 2, "legS") },
				{ p(r("l.l.l"), 0, 0, "legN"), p(r("##", "##"), 0, 1), p(r("##", "##"), 2, 1), p(r("#", "#"), 4, 1), p(r("l.l.l"), 0, 3, "legS") },
				{ p(r("l.l.l"), 1, 0, "legN"), p(r("##", "##"), 0, 1), p(r("##", "##"), 2, 1), p(r("##", "##"), 4, 1), p(r("#", "#"), 6, 1),
						p(r("l.l.l"), 1, 3, "legS") } });
		FORMS.put("lancet", new Part[][] {
				{ p(r("##"), 0, 0) },
				{ p(r(".###.", "#####", ".###."), 0, 0), p(r("eee"), 1, 1) },
				{ p(r("..###..", "#######", "..###.."), 0, 0), p(r("eeeee"), 1, 1) } });
		// scavengers
		FORMS.put("cluster", new Part[][] {
				{ p(r("#"), 0, 0), p(r("#"), 2, 0, "bead") },
				{ p(r("#"), 0, 0), p(r("#"), 2, 0), p(r("#"), 1, 1, "bead") },
				{ p(r("##", "##"), 0, 0), p(r("#"), 3, 0), p(r("##"), 3, 2, "bead"), p(r("#"), 1, 3) } });
		FORMS.put("sponge", new Part[][] {
				{ p(r("##", "##"), 0, 0) },
				{ p(r(".##.", "#.##", "####", ".##."), 0, 0) },
				{ p(r(".####.", "#.####", "####.#", "##.###", ".####."), 0, 0) } });
		FORMS.put("shard", new Part[][] {
				{ p(r("##", ".#"), 0, 0) },
				{ p(r(".##", "###", "##."), 0, 0) },
				{ p(r("..###", ".####", "####.", "###.."), 0, 0) } });
		FORMS.put("brush", new Part[][] {
				{ p(r("##"), 0, 0), p(r("p"), 3, 0, "bristle") },
				{ p(r("##", "##", "##"), 0, 0), p(r("p", ".", "p"), 3, 0, "bristle") },
				{ p(r(".##", "###", "###", ".##"), 0, 0), p(r("p", ".", ".", "p"), 4, 0, "bristle"), p(r("p", "p"), 5, 1, "bristle") } });
		// parasites
		FORMS.put("splinter", new Part[][] {
				{ p(r("#.", ".#"), 0, 0) },
				{ p(r("##.", ".##"), 0, 0) },
				{ p(r("##..", ".##.", "..##"), 0, 0) } });
		FORMS.put("seed", new Part[][] {
				{ p(r("l"), 0, 0, "thread"), p(r("#"), 1, 0) },
				{ p(r("l"), 0, 1, "thread"), p(r(".#", "##", ".#"), 1, 0) },
				{ p(r("ll"), 0, 1, "thread"), p(r(".##", "###", ".##"), 2, 0) } });
	}

	/**
	 * The same forms at TWICE the fidelity: on a half-art-pixel lattice, for the closest
	 * zoom (the client switches at {@code FINE_SCALE} px a tile), still at true size --
	 * a body twice as many pixels across, each half as big. Each class is the drawing
	 * the comparison sheets made at that size (newborn, juvenile, adult), so the
	 * close-up shows the detail the coarse classes compress to one mark: every bead,
	 * every pore, every plate.
	 */
	static final Map<String, Part[][]> FINE = new HashMap<String, Part[][]>();

	static {
		FINE.put("cluster", new Part[][] {
				{ p(r("##", "##"), 0, 0), p(r("##", "##"), 2, 1, "bead") },
				{ p(r("##", "##"), 0, 1), p(r(".##.", "####", "####", ".##."), 2, 0), p(r("##", "##"), 5, 1, "bead") },
				{ p(r(".##.", "####", "####", ".##."), 0, 1), p(r(".##.", "####", "####", ".##."), 3, 4), p(r(".##.", "####", "####", ".##."), 3, -1), p(r(".##.", "####", "####", ".##."), 6, 2), p(r("##", "##"), 9, 1, "bead") } });
		FINE.put("sponge", new Part[][] {
				{ p(r(".##.", "#.##", "####", ".##."), 0, 0) },
				{ p(r(".#####.", "##.####", "#####.#", "#.#####", ".#####."), 0, 0) },
				{ p(r(".########.", "##.####.##", "####.#####", "#.######.#", "#####.####", "##.####.##", ".########."), 0, 0) } });
		FINE.put("shard", new Part[][] {
				{ p(r("..##", ".###", "###.", ".#.."), 0, 0) },
				{ p(r("...###", "..####", ".####.", "####..", "###..."), 0, 0) },
				{ p(r("....######", "...######.", "..######..", ".######...", "######...."), 0, 0) } });
		FINE.put("brush", new Part[][] {
				{ p(r(".##.", "###.", ".##."), 0, 0), p(r("p", ".", "p"), 3, 0, "bristle") },
				{ p(r(".####.", "#####.", "#####.", ".####."), 0, 0), p(r("p", ".", "p", "p"), 5, 0, "bristle") },
				{ p(r(".######..", "########.", "#######..", "########.", "#######..", "########.", ".######.."), 0, 0),
						p(r("p", ".", "p", "p", "p", ".", "p"), 8, 0, "bristle") } });
		FINE.put("splinter", new Part[][] {
				{ p(r("##.", ".##"), 0, 0) },
				{ p(r("##..", ".##.", "..##"), 0, 0) },
				{ p(r("##....", ".###..", "..###.", "...###", "....##"), 0, 0) } });
		FINE.put("seed", new Part[][] {
				{ p(r("l"), 0, 1, "thread"), p(r(".#.", "###", ".#."), 1, 0) },
				{ p(r("ll"), 0, 1, "thread"), p(r(".##", "###", ".##"), 2, 0) },
				{ p(r("ll"), 0, 2, "thread"), p(r("..##.", ".####", "#####", ".####", "..##."), 2, 0) } });
		FINE.put("amoeba", new Part[][] {
				{ p(r(".###", "####", "###.", ".#.."), 0, 0), p(r("e"), 1, 1, "pore") },
				{ p(r("..###..", ".######", "#######", ".#####.", "##..##."), 0, 0), p(r("ee", "ee"), 2, 1, "pore") },
				{ p(r("..###......", ".#####.##..", "##########.", ".##########", "..#########", ".#########.", "##..####...", "#....##...."), 0, 0, "popA"),
						p(r("..###......", ".#####.##..", "##########.", ".##########", "..#########", ".#########.", ".##.####...", "....###...."), 0, 0, "popB"),
						p(r(".ee.", "eeee", "eeee", ".ee."), 4, 2, "pore") } });
		FINE.put("diatom", new Part[][] {
				{ p(r(".####.", "######", ".####."), 0, 0), p(r("e.e"), 1, 1, "pore") },
				{ p(r(".######.", "########", "########", ".######."), 0, 0), p(r("e.e.e", "e.e.e"), 1, 1, "pore") },
				{ p(r("..########..", ".##########.", "############", "############", ".##########.", "..########.."), 0, 0),
						p(r("e.e.e.e.e", ".........", "e.e.e.e.e"), 1, 1, "pore") } });
		FINE.put("crawler", new Part[][] {
				{ p(r("l.l."), 0, 0, "legN"), p(r("##", "##"), 0, 1), p(r("##", "##"), 2, 1), p(r("#", "#"), 4, 1), p(r("l.l."), 0, 3, "legS") },
				{ p(r("l.l.l.l"), 1, -1, "legN"), p(r(".##", "###", "###", ".##"), 0, 0), p(r(".##", "###", "###", ".##"), 3, 0),
						p(r(".##", "###", "###", ".##"), 6, 0), p(r("#.", "##", "#."), 9, 1), p(r("l.l.l.l"), 1, 4, "legS") },
				{ p(r("l.l.l.l.l"), 2, -1, "legN"), p(r("##", "##"), 0, 1), p(r(".##", "###", "###", ".##"), 1, 0), p(r(".###", "####", "####", ".###"), 3, 0),
						p(r(".###", "####", "####", ".###"), 6, 0), p(r(".##.", "####", "####", ".##."), 9, 0), p(r("#.", "##", "#."), 12, 1),
						p(r("l.l.l.l.l"), 2, 4, "legS") } });
		FINE.put("lancet", new Part[][] {
				{ p(r(".###.", "#####", ".###."), 0, 0), p(r("eee"), 1, 1) },
				{ p(r("..#####..", "#########", "..#####.."), 0, 0), p(r("eeeee"), 2, 1), p(r("#", ".", "#"), 9, 0) },
				{ p(r("....######...", ".###########.", "#############", ".###########.", "....######..."), 0, 0), p(r("eeeeeee"), 3, 2),
						p(r("#", ".", "#"), 13, 1) } });
	}

	/** The forms each plan wears, indexed by {@link ProcCreature.Phenotype#form}: 0 the
	 *  grazer, 1 the parasite, 3 the scavenger, 4 the hunter. The unworn plans (2, 5)
	 *  wear the grazer's, so any phenotype draws. */
	static final String[][] BY_PLAN = {
			{ "amoeba", "diatom" },
			{ "splinter", "seed" },
			{ "amoeba", "diatom" },
			{ "cluster", "sponge", "shard", "brush" },
			{ "crawler", "lancet" },
			{ "amoeba", "diatom" },
	};

	/** One art-pixel of motion per tag over the {@link #FRAMES} gait frames: {dx, dy}. */
	static final Map<String, int[][]> GAIT = new HashMap<String, int[][]>();

	static {
		GAIT.put("legN", new int[][] { { 0, 0 }, { 0, 0 }, { 1, 0 }, { 1, 0 }, { 0, 0 }, { 0, 0 }, { -1, 0 }, { -1, 0 } });
		GAIT.put("legS", new int[][] { { 0, 0 }, { 0, 0 }, { -1, 0 }, { -1, 0 }, { 0, 0 }, { 0, 0 }, { 1, 0 }, { 1, 0 } });
		GAIT.put("thread", new int[][] { { 0, 0 }, { 0, 1 }, { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, -1 }, { 0, -1 }, { 0, 0 } });
		GAIT.put("bristle", new int[][] { { 0, 0 }, { 0, 0 }, { -1, 0 }, { -1, 0 }, { 0, 0 }, { 0, 0 }, { -1, 0 }, { -1, 0 } });
		GAIT.put("pore", new int[][] { { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 0 }, { 1, 0 }, { 1, 0 }, { 1, 0 }, { 1, 0 } });
		GAIT.put("bead", new int[][] { { 0, 0 }, { 0, 0 }, { 0, 0 }, { 0, 1 }, { 0, 1 }, { 0, 0 }, { 0, 0 }, { 0, 0 } });
	}

	/** The form a phenotype wears. */
	public static String formName(ProcCreature.Phenotype ph) {
		String[] forms = BY_PLAN[Math.max(0, Math.min(BY_PLAN.length - 1, ph.form))];
		return forms[Math.floorMod(ph.variant, forms.length)];
	}

	/** Whether the phenotype wears its form mirrored across the body's axis. */
	public static boolean mirrored(ProcCreature.Phenotype ph) {
		String[] forms = BY_PLAN[Math.max(0, Math.min(BY_PLAN.length - 1, ph.form))];
		return (Math.floorMod(ph.variant, 8) / forms.length) % 2 == 1;
	}

	/** Every form name, for the catalog and the tests. */
	public static java.util.Set<String> forms() {
		return java.util.Collections.unmodifiableSet(FORMS.keySet());
	}

	/**
	 * The size class a body of this radius (in tiles) is drawn at: what its diameter
	 * is in world art-pixels, rounded to the class whose drawing is that wide. Below
	 * 3 art-px small, below 5 medium, else large. The client picks by the same rule
	 * (client/src/atlas.ts sizeClass); the two must agree.
	 */
	public static int sizeClass(double radiusTiles) {
		double d = radiusTiles * 2 * GroundTextures.ART;
		return d < 3 ? SMALL : d < 5 ? MEDIUM : LARGE;
	}

	/** A drawn cell: its mark and which part it belongs to. */
	public static final class Cell {
		public final char ch;
		public final int part;

		Cell(char ch, int part) {
			this.ch = ch;
			this.part = part;
		}
	}

	private static long key(int x, int y) {
		return ((long) x << 32) ^ (y & 0xffffffffL);
	}

	public static int cx(long k) {
		return (int) (k >> 32);
	}

	public static int cy(long k) {
		return (int) k;
	}

	/** Quarter turns counter-clockwise (east to north) for an atlas column: 0 east,
	 *  2 south, 4 west, 6 north; a diagonal wears the cardinal before it. */
	static int turnsFor(int dir) {
		int cardinal = Math.floorMod(dir, 8) / 2; // 0 E, 1 S, 2 W, 3 N
		return new int[] { 0, 3, 2, 1 }[cardinal];
	}

	/**
	 * The body's cells at a size class, gait frame and atlas column (heading bucket):
	 * built east-facing, posed, mirrored if the variant says so, then turned.
	 */
	public static Map<Long, Cell> cells(String form, boolean mirror, int cls, int frame, int dir) {
		return cells(form, mirror, cls, frame, dir, false);
	}

	/** As above, at twice the fidelity when {@code fine}: the half-art-pixel drawings. */
	public static Map<Long, Cell> cells(String form, boolean mirror, int cls, int frame, int dir, boolean fine) {
		Part[] parts = (fine ? FINE : FORMS).get(form)[Math.max(0, Math.min(CLASSES - 1, cls))];
		int f = Math.floorMod(frame, FRAMES), turns = turnsFor(dir);
		Map<Long, Cell> out = new HashMap<Long, Cell>();
		for (int i = 0; i < parts.length; i++) {
			Part pt = parts[i];
			if (pt.tag.equals("popA") && f >= 4 || pt.tag.equals("popB") && f < 4) {
				continue; // an amoeba changes its outline; it does not slide
			}
			int[] g = GAIT.containsKey(pt.tag) ? GAIT.get(pt.tag)[f] : new int[] { 0, 0 };
			for (int y = 0; y < pt.st.length; y++) {
				for (int x = 0; x < pt.st[y].length(); x++) {
					char ch = pt.st[y].charAt(x);
					if (ch == '.') {
						continue;
					}
					int X = pt.x + x + g[0], Y = pt.y + y + g[1];
					if (mirror) {
						Y = -Y;
					}
					for (int t = 0; t < turns; t++) {
						int nx = Y, ny = -X;
						X = nx;
						Y = ny;
					}
					out.put(key(X, Y), new Cell(ch, i));
				}
			}
		}
		return out;
	}

	public static Map<Long, Cell> cells(ProcCreature.Phenotype ph, int cls, int frame, int dir) {
		return cells(formName(ph), mirrored(ph), cls, frame, dir);
	}

	/** The cells' box: {x0, y0, x1, y1}. */
	public static int[] bounds(Map<Long, Cell> cells) {
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
		for (long k : cells.keySet()) {
			x0 = Math.min(x0, cx(k));
			y0 = Math.min(y0, cy(k));
			x1 = Math.max(x1, cx(k));
			y1 = Math.max(y1, cy(k));
		}
		return new int[] { x0, y0, x1, y1 };
	}

	/**
	 * The lit colour of every cell, from the body colour: each part's crest (no tissue
	 * of its own above) the brightest, the row under it lit, its underside sunk; the
	 * crevice where a later part lies against it the deepest; legs, threads and dark
	 * marks the deepest; a pale bristle lit. All of it shade()/mixWhite() of the base.
	 */
	public static Map<Long, Integer> light(Map<Long, Cell> cells, int base) {
		int tip = mixWhite(base, 0.5), hi = mixWhite(base, 0.22), lo = shade(base, 0.64), deep = shade(base, 0.44);
		Map<Long, Integer> out = new HashMap<Long, Integer>();
		for (Map.Entry<Long, Cell> en : cells.entrySet()) {
			long k = en.getKey();
			Cell c = en.getValue();
			int x = cx(k), y = cy(k), col;
			if (c.ch == '#') {
				int top = 0, bot = 0;
				while (tissue(cells, x, y - top - 1, c.part)) {
					top++;
				}
				while (tissue(cells, x, y + bot + 1, c.part)) {
					bot++;
				}
				col = top == 0 ? tip : top == 1 ? hi : bot == 0 ? deep : bot == 1 ? lo : base;
				for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
					Cell o = cells.get(key(x + d[0], y + d[1]));
					if (o != null && o.ch == '#' && o.part > c.part) {
						col = deep;
					}
				}
			} else if (c.ch == 'p') {
				col = hi;
			} else {
				col = deep;
			}
			out.put(k, col);
		}
		return out;
	}

	private static boolean tissue(Map<Long, Cell> cells, int x, int y, int part) {
		Cell o = cells.get(key(x, y));
		return o != null && o.ch == '#' && o.part == part;
	}

	/**
	 * Paints one specimen frame centred on (cx, cy) -- a lattice line -- at {@code px}
	 * pixels to the art-pixel: its blocky shadow (§4's translucency 2), then the body.
	 * A flyer is lifted two art-pixels with its shadow left on the ground below.
	 */
	public static void paint(Graphics2D g, int cx, int cy, int px, ProcCreature.Phenotype ph, int cls, int frame, int dir) {
		paint(g, cx, cy, px, ph, cls, frame, dir, false);
	}

	/** As above; {@code fine} paints the half-art-pixel drawings, {@code px} then being
	 *  pixels to the HALF art-pixel. */
	public static void paint(Graphics2D g, int cx, int cy, int px, ProcCreature.Phenotype ph, int cls, int frame, int dir, boolean fine) {
		Map<Long, Cell> cells = cells(formName(ph), mirrored(ph), cls, frame, dir, fine);
		int[] b = bounds(cells);
		int mx = Math.floorDiv(b[0] + b[2], 2), my = Math.floorDiv(b[1] + b[3], 2);
		int w = b[2] - b[0] + 1, h = b[3] - b[1] + 1, lift = ph.flying ? (fine ? 4 : 2) : 0;
		int rx = (int) Math.round(Math.max(w, h) * 0.46), ry = Math.max(1, (int) Math.round(Math.min(w, h) * 0.32));
		rx = Math.max(1, rx);
		g.setColor(new Color(0, 0, 0, ph.flying ? 80 : 115));
		for (int y = -ry; y <= ry; y++) {
			for (int x = -rx; x <= rx; x++) {
				if ((double) x * x / (rx * rx) + (double) y * y / (ry * ry) <= 1) {
					g.fillRect(cx + x * px, cy + (y + 1) * px, px, px);
				}
			}
		}
		for (Map.Entry<Long, Integer> en : light(cells, ph.color).entrySet()) {
			long k = en.getKey();
			g.setColor(new Color(en.getValue()));
			g.fillRect(cx + (cx(k) - mx) * px, cy + (cy(k) - my - lift) * px, px, px);
		}
	}

	static int shade(int rgb, double f) {
		return (cl((int) (((rgb >> 16) & 255) * f)) << 16) | (cl((int) (((rgb >> 8) & 255) * f)) << 8)
				| cl((int) ((rgb & 255) * f));
	}

	static int mixWhite(int rgb, double t) {
		int r = (int) (((rgb >> 16) & 255) * (1 - t) + 255 * t);
		int gg = (int) (((rgb >> 8) & 255) * (1 - t) + 255 * t);
		int bl = (int) ((rgb & 255) * (1 - t) + 255 * t);
		return (cl(r) << 16) | (cl(gg) << 8) | cl(bl);
	}

	private static int cl(int v) {
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}
}
