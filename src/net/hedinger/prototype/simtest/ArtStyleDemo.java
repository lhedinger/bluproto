package net.hedinger.prototype.simtest;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Art-style prototype harness (scratch tool, not a scenario test). Sweeps the
 * two main dials -- art-pixel resolution and intra-terrain texture strength --
 * on the natural palette, and shows the CRT pass as a separate toggleable
 * overlay. Pure code, deterministic, no assets. Writes several out/art_*.png.
 */
public class ArtStyleDemo {

	static final int WT = 12, HT = 9; // world size in tiles

	// Natural palette: 5 terrain ramps {shadow, base, highlight}.
	static final int[][] NATURAL = {
			{ 0x1a3a60, 0x24568c, 0x3172b0 }, { 0x8a6d3c, 0xb59a5f, 0xdcc48c },
			{ 0x2a4d24, 0x3f7a38, 0x5f9850 }, { 0x40301f, 0x63472e, 0x866543 },
			{ 0x3a3d47, 0x585d69, 0x82879a } };

	static final int[] SPECIES = { 0xd8483a, 0xe8c84a, 0x8a56d0, 0x3fb6c8, 0xe08a2a };
	static final double[] EMULT = { 1.0, 1.0, 1.6, 1.0, 2.2, 1.0, 1.3, 1.0, 1.7 };

	/** Everything that depends on the chosen resolution R (art-px per tile). */
	static class Field {
		int R, AW, AH;
		double[][] elev;
		double[] bnd;
		double bw;
		List<int[]> spots; // {tileCol, tileRow, speciesIdx, multIdx}
	}

	public static void main(String[] args) throws Exception {
		new File("out").mkdirs();
		if (args.length > 0 && args[0].equals("anim")) {
			animate();
			return;
		}

		// --- Resolution dial: fixed calm texture, varying art-px per tile ---
		int[] res = { 8, 12, 20, 32 };
		BufferedImage[] rp = new BufferedImage[res.length];
		String[] rl = new String[res.length];
		for (int i = 0; i < res.length; i++) {
			Field f = buildField(res[i]);
			int scale = Math.max(1, Math.round(700f / f.AW));
			rp[i] = scene(f, scale, NATURAL, 0.7, 1.44, 0.30, 0.82, false);
			rl[i] = res[i] + " px/tile   (" + f.AW + "x" + f.AH + " art-px, " + scale + "x)";
		}
		ImageIO.write(grid2(rp, rl, "Resolution dial - natural palette, calm texture"),
				"png", new File("out/art_resolution.png"));

		// --- Texture dial: fixed resolution, varying shading strength ---
		Field f12 = buildField(12);
		double[][] tex = { // {strength, freqTile, loThresh, hiThresh}
				{ 0.0, 1.2, 0.30, 0.82 }, { 0.45, 1.2, 0.24, 0.86 },
				{ 0.7, 1.44, 0.30, 0.82 }, { 1.0, 1.9, 0.36, 0.72 } };
		String[] tl = { "flat (base only)", "subtle", "calm", "textured" };
		BufferedImage[] tp = new BufferedImage[tex.length];
		for (int i = 0; i < tex.length; i++) {
			tp[i] = scene(f12, 5, NATURAL, tex[i][0], tex[i][1], tex[i][2], tex[i][3], false);
		}
		ImageIO.write(grid2(tp, tl, "Texture dial - natural palette, 12 px/tile"),
				"png", new File("out/art_texture.png"));

		// --- CRT as a separate toggleable overlay: off vs on ---
		BufferedImage crtOff = scene(f12, 5, NATURAL, 0.7, 1.44, 0.30, 0.82, false);
		BufferedImage crtOn = scene(f12, 5, NATURAL, 0.7, 1.44, 0.30, 0.82, true);
		ImageIO.write(grid2(new BufferedImage[] { crtOff, crtOn },
				new String[] { "CRT overlay OFF", "CRT overlay ON" },
				"CRT post-process (toggleable) - same base frame"), "png", new File("out/art_crt.png"));

		// --- Creative entity variations at the 12 px/tile scale ---
		ImageIO.write(bestiary(), "png", new File("out/art_entities.png"));
		ImageIO.write(population(f12), "png", new File("out/art_population.png"));
		ImageIO.write(motion(), "png", new File("out/art_motion.png"));

		System.out.println("wrote art_resolution/texture/crt.png + art_entities/population/motion.png");
	}

	/** Renders an animated clip: organisms wander the island, turning to their
	 * heading and animating from the phase clock. Writes PNG frames for ffmpeg. */
	static void animate() throws Exception {
		Field f = buildField(12);
		int scale = 6;
		BufferedImage base = upscale(renderGround(f, NATURAL, 0.6, 1.44, 0.30, 0.82), f.AW, f.AH, scale);
		drawGrid(base, f.R * scale);

		int n = 16;
		double[] x = new double[n], y = new double[n], hd = new double[n];
		TD[] td = new TD[n];
		List<int[]> land = new ArrayList<int[]>();
		for (int tc = 1; tc < WT - 1; tc++) {
			for (int tr = 1; tr < HT - 1; tr++) {
				int b = bandOf(f.elev[tc * f.R + f.R / 2][tr * f.R + f.R / 2], f.bnd);
				if (b >= 2) {
					land.add(new int[] { tc, tr });
				}
			}
		}
		for (int i = 0; i < n; i++) {
			int[] t = land.get((i * 41 + 3) % land.size());
			x[i] = t[0] * f.R + f.R / 2.0;
			y[i] = t[1] * f.R + f.R / 2.0;
			hd[i] = 2 * Math.PI * hash(i, 9, 2);
			td[i] = new TD(2 + (i % 3 == 0 ? 1 : 0), ECOLORS[i % ECOLORS.length], i % 6,
					2 + (i / 6) % 3, i % 2 == 0, (i / 4) % 3, (i / 2) % 3, i % 5 == 0, hd[i]);
		}

		File dir = new File("out/frames");
		dir.mkdirs();
		for (File old : dir.listFiles()) {
			old.delete();
		}
		int frames = 160;
		for (int fr = 0; fr < frames; fr++) {
			BufferedImage img = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.drawImage(base, 0, 0, null);
			g.dispose();
			for (int i = 0; i < n; i++) {
				hd[i] += (hash(fr, i, 3) - 0.5) * 0.35; // gentle wander
				double nx = x[i] + Math.cos(hd[i]) * 0.4, ny = y[i] + Math.sin(hd[i]) * 0.4;
				if (onLand(f, nx, ny)) {
					x[i] = nx;
					y[i] = ny;
				} else {
					hd[i] += Math.PI * 0.75; // turn away from water/edge
				}
				td[i].heading = hd[i];
				drawTopdown(img, scale, (int) Math.round(x[i]), (int) Math.round(y[i]), td[i], fr * 0.35 + i * 1.3);
			}
			ImageIO.write(img, "png", new File(dir, String.format("f%04d.png", fr)));
		}
		System.out.println("wrote " + frames + " frames to out/frames/");
	}

	static boolean onLand(Field f, double x, double y) {
		int ix = (int) x, iy = (int) y;
		if (ix < 2 || iy < 2 || ix >= f.AW - 2 || iy >= f.AH - 2) {
			return false;
		}
		return bandOf(f.elev[ix][iy], f.bnd) >= 1; // sand..stone, avoid deep water
	}

	/**
	 * Demonstrates rotation (8 quantised directions) and phase-driven animation
	 * (leg scuttle, worm undulation, cilia rotation) -- all from the same
	 * parametric draw function, no sprite sheets.
	 */
	static BufferedImage motion() {
		int thumb = 15, scale = 9, cell = thumb * scale, pad = 18, gap = 8, top = 30, labW = 150;
		// rows: rotation (8 dirs), then 3 animation cycles at 8 frames each.
		int rowN = 8;
		String[] rowLabel = { "rotation (8 dir)", "walk cycle (arthropod)", "undulate (worm)", "pulse (microbe)" };
		int rows = rowLabel.length;
		int W = pad + labW + rowN * cell + (rowN - 1) * gap + pad;
		int H = top + pad + rows * cell + (rows - 1) * (gap + 16) + pad;
		BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(new Color(20, 21, 27));
		g.fillRect(0, 0, W, H);
		g.setColor(new Color(235, 238, 245));
		g.setFont(new Font("SansSerif", Font.BOLD, 17));
		g.drawString("Rotation + animation from one parametric function (no sprite sheets), zoomed", pad, 21);
		g.setFont(new Font("SansSerif", Font.BOLD, 13));
		int red = ECOLORS[0], green = 0x8fbf3a, cyan = 0x3fb6c8;
		for (int row = 0; row < rows; row++) {
			int cy = top + pad + row * (cell + gap + 16);
			g.setColor(new Color(200, 205, 216));
			g.drawString(rowLabel[row], pad, cy + cell / 2);
			for (int col = 0; col < rowN; col++) {
				TD c;
				double phase = 0;
				if (row == 0) { // rotation: face each of 8 directions, static
					c = new TD(3, red, 0, 3, true, 1, 0, true, 2 * Math.PI * col / rowN);
				} else if (row == 1) { // walk cycle
					c = new TD(3, red, 0, 3, true, 1, 0, true, -Math.PI / 2);
					phase = 2 * Math.PI * col / rowN;
				} else if (row == 2) { // worm undulation
					c = new TD(3, green, 3, 2, false, 0, 1, true, -Math.PI / 2);
					phase = 2 * Math.PI * col / rowN;
				} else { // microbe pulse
					c = new TD(3, cyan, 1, 3, false, 2, 0, false, -Math.PI / 2);
					phase = 2 * Math.PI * col / rowN;
				}
				BufferedImage t = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_RGB);
				Graphics2D tg = t.createGraphics();
				tg.setColor(new Color(34, 36, 44));
				tg.fillRect(0, 0, cell, cell);
				tg.dispose();
				drawTopdown(t, scale, thumb / 2, thumb / 2, c, phase);
				g.drawImage(t, pad + labW + col * (cell + gap), cy, null);
			}
		}
		g.dispose();
		return out;
	}

	// ---- creative entities -------------------------------------------------

	static final int[] ECOLORS = {
			0xd8483a, 0xe8a53a, 0xe8c84a, 0x8fbf3a, 0x3fb36a, 0x3fb6c8,
			0x4a86d8, 0x8a56d0, 0xd05fb0, 0xb0836a, 0x9aa0ad, 0xdfe2ea };

	static double sq(double v) {
		return v * v;
	}

	/**
	 * A top-down abstract organism: a dorsal silhouette oriented to a heading,
	 * with appendages splaying outward. form: 0 arthropod (streamlined + bilateral
	 * legs), 1 radial (round + cilia), 2 dart (heading triangle), 3 segmented
	 * (worm), 4 spider (small body + long legs), 5 amoeba (irregular + nucleus).
	 */
	static class TD {
		int r, color, form, legs, core, pattern;
		boolean antennae, tail;
		double heading;

		TD(int r, int color, int form, int legs, boolean antennae, int core, int pattern, boolean tail, double h) {
			this.r = r;
			this.color = color;
			this.form = form;
			this.legs = legs;
			this.antennae = antennae;
			this.core = core;
			this.pattern = pattern;
			this.tail = tail;
			this.heading = h;
		}
	}

	/**
	 * True if local (along-heading, perpendicular) is inside the body silhouette.
	 * {@code phase} animates the shape: worms undulate, amoebas wobble.
	 */
	static boolean inForm(int form, double la, double pe, int r, int seed, double phase) {
		switch (form) {
		case 1:
			return la * la + pe * pe <= r * r + r * 0.5;
		case 2:
			return la <= r + 0.3 && la >= -r - 0.3 && Math.abs(pe) <= (r - la) * 0.5 + 0.4;
		case 3: // segmented worm, undulating
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
		default: // streamlined oval, longer along heading
			return sq(la / (r + 0.8)) + sq(pe / Math.max(0.7, r - 0.3)) <= 1.05;
		}
	}

	static int[] wpt(double la, double pe, double ux, double uy, double rx, double ry) {
		return new int[] { (int) Math.round(la * ux + pe * rx), (int) Math.round(la * uy + pe * ry) };
	}

	static int key(int dx, int dy) {
		return (dx + 32) * 128 + (dy + 32);
	}

	static void drawTopdown(BufferedImage img, int scale, int cx, int cy, TD c) {
		drawTopdown(img, scale, cx, cy, c, 0);
	}

	static void drawTopdown(BufferedImage img, int scale, int cx, int cy, TD c, double phase) {
		double ux = Math.cos(c.heading), uy = Math.sin(c.heading), rx = -uy, ry = ux;
		int r = c.r;
		java.util.HashSet<Integer> body = new java.util.HashSet<Integer>();
		for (double la = -r - 2; la <= r + 2; la += 0.5) {
			for (double pe = -r - 2; pe <= r + 2; pe += 0.5) {
				if (inForm(c.form, la, pe, r, c.color, phase)) {
					int[] w = wpt(la, pe, ux, uy, rx, ry);
					body.add(key(w[0], w[1]));
				}
			}
		}
		// Ambient shadow: faint halo just outside the silhouette (south-biased).
		for (int p : body) {
			int dx = p / 128 - 32, dy = p % 128 - 32;
			if (!body.contains(key(dx, dy + 1))) {
				blendBlock(img, scale, cx + dx, cy + dy + 1, 0x000000, 0.22);
			}
		}
		// Appendages (drawn under the body's edge).
		if (c.form == 0 || c.form == 4) { // bilateral legs, scuttling
			int pairs = c.legs;
			double half = (c.form == 4 ? r - 1 : r - 0.2), len = (c.form == 4 ? 2.4 : 1.4);
			for (int i = 0; i < pairs; i++) {
				double la = pairs == 1 ? 0 : lerp(-r + 0.6, r - 0.6, i / (double) (pairs - 1));
				double drive = 0.55 + 0.45 * Math.sin(phase + i * 2.1); // per-leg gait
				for (double e = half; e <= half + len * drive; e += 0.7) {
					int[] a = wpt(la, e, ux, uy, rx, ry), b = wpt(la, -e, ux, uy, rx, ry);
					stamp(img, scale, cx + a[0], cy + a[1], shade(c.color, 0.5));
					stamp(img, scale, cx + b[0], cy + b[1], shade(c.color, 0.5));
				}
			}
		} else if (c.form == 1) { // radial cilia, rotating + pulsing
			int n = c.legs * 2 + 5;
			double base = r + 0.8 + 0.35 * Math.sin(phase * 1.5);
			for (int i = 0; i < n; i++) {
				double ang = 2 * Math.PI * i / n + phase * 0.25;
				int[] a = wpt(Math.cos(ang) * base, Math.sin(ang) * base, ux, uy, rx, ry);
				stamp(img, scale, cx + a[0], cy + a[1], shade(c.color, 0.5));
			}
		}
		// Body, lit from screen-north (world dy) regardless of heading.
		for (int p : body) {
			int dx = p / 128 - 32, dy = p % 128 - 32;
			double ts = (dy + r) / (2.0 * r);
			int col = ts < 0.4 ? mixWhite(c.color, 0.4) : (ts > 0.72 ? shade(c.color, 0.6) : c.color);
			stamp(img, scale, cx + dx, cy + dy, col);
		}
		// Nucleus / core.
		if (c.core > 0) {
			stamp(img, scale, cx, cy, c.core == 1 ? shade(c.color, 0.5) : mixWhite(c.color, 0.55));
		}
		// Dorsal stripe along heading, or a lit head at the front.
		if (c.pattern == 1) {
			for (double la = -r; la <= r; la += 0.5) {
				int[] a = wpt(la, 0, ux, uy, rx, ry);
				if (body.contains(key(a[0], a[1]))) {
					stamp(img, scale, cx + a[0], cy + a[1], shade(c.color, 0.72));
				}
			}
		} else if (c.pattern == 2) {
			int[] a = wpt(r - 0.3, 0, ux, uy, rx, ry);
			if (body.contains(key(a[0], a[1]))) {
				stamp(img, scale, cx + a[0], cy + a[1], mixWhite(c.color, 0.55));
			}
		}
		// Antennae forward, tail aft.
		if (c.antennae) {
			int[] a = wpt(r + 0.9, 0.9, ux, uy, rx, ry), b = wpt(r + 0.9, -0.9, ux, uy, rx, ry);
			stamp(img, scale, cx + a[0], cy + a[1], shade(c.color, 0.55));
			stamp(img, scale, cx + b[0], cy + b[1], shade(c.color, 0.55));
		}
		if (c.tail) {
			int[] t = wpt(-(r + 0.9), 0, ux, uy, rx, ry);
			stamp(img, scale, cx + t[0], cy + t[1], shade(c.color, 0.6));
		}
	}

	/** Zoomed grid of top-down organisms (all facing up so forms are comparable). */
	static BufferedImage bestiary() {
		int cols = 6, rows = 4, thumb = 15, scale = 9, cell = thumb * scale;
		int pad = 18, gap = 8, top = 30;
		int W = pad * 2 + cols * cell + (cols - 1) * gap;
		int H = top + pad * 2 + rows * cell + (rows - 1) * gap;
		BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(new Color(20, 21, 27));
		g.fillRect(0, 0, W, H);
		g.setColor(new Color(235, 238, 245));
		g.setFont(new Font("SansSerif", Font.BOLD, 17));
		g.drawString("Top-down organisms - facing up, at 12 px/tile (radius 2-3 art-px), zoomed", pad, 21);
		double up = -Math.PI / 2;
		for (int i = 0; i < cols * rows; i++) {
			int r = 2 + (i % 3 == 2 ? 1 : 0);
			TD c = new TD(r, ECOLORS[i % ECOLORS.length], i % 6, 2 + (i / 6) % 3,
					i % 2 == 0, (i / 3) % 3, (i / 2) % 3, i % 4 == 0, up);
			BufferedImage t = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_RGB);
			Graphics2D tg = t.createGraphics();
			tg.setColor(new Color(34, 36, 44));
			tg.fillRect(0, 0, cell, cell);
			tg.dispose();
			drawTopdown(t, scale, thumb / 2, thumb / 2, c);
			int cx = pad + (i % cols) * (cell + gap);
			int cy = top + pad + (i / cols) * (cell + gap);
			g.drawImage(t, cx, cy, null);
		}
		g.dispose();
		return out;
	}

	/** The natural island populated with varied top-down organisms at true scale. */
	static BufferedImage population(Field f) {
		int scale = 6;
		int[][] a = renderGround(f, NATURAL, 0.6, 1.44, 0.30, 0.82);
		BufferedImage p = upscale(a, f.AW, f.AH, scale);
		drawGrid(p, f.R * scale);
		int placed = 0;
		for (int tc = 1; tc < WT - 1 && placed < 16; tc++) {
			for (int tr = 1; tr < HT - 1 && placed < 16; tr++) {
				int b = bandOf(f.elev[tc * f.R + f.R / 2][tr * f.R + f.R / 2], f.bnd);
				if ((b == 2 || b == 3) && ((tc * 7 + tr * 5) % 3 == 0)) {
					int s = tc * 31 + tr * 17;
					TD c = new TD(2 + (s % 3 == 0 ? 1 : 0), ECOLORS[(s / 7) % ECOLORS.length],
							s % 6, 2 + (s / 6) % 3, s % 2 == 0, (s / 4) % 3, (s / 2) % 3, s % 5 == 0,
							(s % 16) / 16.0 * 2 * Math.PI);
					drawTopdown(p, scale, tc * f.R + f.R / 2, tr * f.R + f.R / 2, c);
					placed++;
				}
			}
		}
		return labelBar(p, "Natural island populated with top-down organisms (true 12 px/tile scale)");
	}

	static BufferedImage labelBar(BufferedImage panel, String text) {
		int lab = 26;
		BufferedImage out = new BufferedImage(panel.getWidth(), panel.getHeight() + lab, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(new Color(16, 16, 20));
		g.fillRect(0, 0, out.getWidth(), out.getHeight());
		g.setColor(new Color(214, 218, 228));
		g.setFont(new Font("SansSerif", Font.BOLD, 16));
		g.drawString(text, 6, 18);
		g.drawImage(panel, 0, lab, null);
		g.dispose();
		return out;
	}

	// ---- scene assembly ----------------------------------------------------

	static BufferedImage scene(Field f, int scale, int[][] pal,
			double s, double freqT, double lo, double hi, boolean crt) {
		int[][] a = renderGround(f, pal, s, freqT, lo, hi);
		BufferedImage p = upscale(a, f.AW, f.AH, scale);
		drawGrid(p, f.R * scale);
		for (int[] sp : f.spots) {
			int r = Math.max(1, (int) Math.round(f.R / 8.0 * EMULT[sp[3]]));
			drawEntity(p, scale, sp[0] * f.R + f.R / 2, sp[1] * f.R + f.R / 2, r, SPECIES[sp[2]]);
		}
		if (crt) {
			crt(p);
		}
		return p;
	}

	static Field buildField(int R) {
		Field f = new Field();
		f.R = R;
		f.AW = WT * R;
		f.AH = HT * R;
		f.elev = new double[f.AW][f.AH];
		double[] flat = new double[f.AW * f.AH];
		double min = 1e9, max = -1e9;
		for (int x = 0; x < f.AW; x++) {
			for (int y = 0; y < f.AH; y++) {
				double e = islandElevation(x, y, R);
				f.elev[x][y] = e;
				flat[x * f.AH + y] = e;
				min = Math.min(min, e);
				max = Math.max(max, e);
			}
		}
		double[] sorted = flat.clone();
		Arrays.sort(sorted);
		f.bnd = new double[] { pct(sorted, 0.30), pct(sorted, 0.38), pct(sorted, 0.74), pct(sorted, 0.89) };
		f.bw = (max - min) * 0.028;
		f.spots = entitySpots(f);
		return f;
	}

	/** Base-dominant intra-terrain shading (strength s) + noise-dithered edges. */
	static int[][] renderGround(Field f, int[][] pal, double s, double freqT, double lo, double hi) {
		int[][] a = new int[f.AW][f.AH];
		for (int x = 0; x < f.AW; x++) {
			for (int y = 0; y < f.AH; y++) {
				double e = f.elev[x][y];
				int terr = bandOf(e, f.bnd);
				for (int i = 0; i < f.bnd.length; i++) {
					if (Math.abs(e - f.bnd[i]) < f.bw) {
						double t = (e - (f.bnd[i] - f.bw)) / (2 * f.bw);
						terr = hash(x, y, 7) < t ? i + 1 : i;
						break;
					}
				}
				double wx = x / (double) f.R, wy = y / (double) f.R;
				double n = fbm(wx * freqT, wy * freqT, 2, 91);
				int base = pal[terr][1];
				int col = n < lo ? mix(base, pal[terr][0], s) : (n > hi ? mix(base, pal[terr][2], s) : base);
				a[x][y] = col;
			}
		}
		return a;
	}

	static double islandElevation(int x, int y, int R) {
		double wx = x / (double) R, wy = y / (double) R; // tile units
		double nx = wx / WT, ny = wy / HT, cx = nx - 0.5, cy = ny - 0.5;
		double dist = Math.sqrt(cx * cx + cy * cy) * 1.85;
		return fbm(wx * 0.66, wy * 0.66, 5, 1) * 0.72 + (1.0 - dist) * 0.6;
	}

	static int bandOf(double e, double[] bnd) {
		int k = 0;
		for (double b : bnd) {
			if (e >= b) {
				k++;
			}
		}
		return k;
	}

	static List<int[]> entitySpots(Field f) {
		List<int[]> land = new ArrayList<int[]>();
		for (int tc = 1; tc < WT - 1; tc++) {
			for (int tr = 1; tr < HT - 1; tr++) {
				int b = bandOf(f.elev[tc * f.R + f.R / 2][tr * f.R + f.R / 2], f.bnd);
				if (b == 2 || b == 3) {
					land.add(new int[] { tc, tr });
				}
			}
		}
		List<int[]> out = new ArrayList<int[]>();
		for (int k = 0; k < 9 && !land.isEmpty(); k++) {
			int[] t = land.get((k * 37 + 5) % land.size());
			out.add(new int[] { t[0], t[1], k % SPECIES.length, k % EMULT.length });
		}
		return out;
	}

	// ---- overlays / post ---------------------------------------------------

	static void drawGrid(BufferedImage img, int step) {
		int w = img.getWidth(), h = img.getHeight();
		for (int x = 0; x <= w; x += step) {
			for (int y = 0; y < h; y++) {
				blend(img, x, y, 0x000000, 0.24);
			}
		}
		for (int y = 0; y <= h; y += step) {
			for (int x = 0; x < w; x++) {
				blend(img, x, y, 0x000000, 0.24);
			}
		}
	}

	static void drawEntity(BufferedImage img, int scale, int cxArt, int cyArt, int r, int base) {
		for (int dx = -r; dx <= r; dx++) {
			if (dx * dx <= r * r) {
				blendBlock(img, scale, cxArt + dx, cyArt + r, 0x000000, 0.28);
			}
		}
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				double d = Math.sqrt(dx * dx + dy * dy);
				if (d > r + 0.25) {
					continue;
				}
				int col;
				if (r >= 2 && d > r - 1.0) {
					col = shade(base, 0.35);
				} else {
					double ts = (dy + r) / (2.0 * r);
					col = ts < 0.4 ? mixWhite(base, 0.4) : (ts > 0.72 ? shade(base, 0.62) : base);
				}
				stamp(img, scale, cxArt + dx, cyArt + dy, col);
			}
		}
		if (r >= 3) {
			stamp(img, scale, cxArt - Math.max(1, r / 3), cyArt - 1, 0xf4f6ff);
			stamp(img, scale, cxArt + Math.max(1, r / 3), cyArt - 1, 0xf4f6ff);
		}
	}

	static void crt(BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		double cx = w / 2.0, cy = h / 2.0, rmax = Math.sqrt(cx * cx + cy * cy);
		for (int y = 0; y < h; y++) {
			double scan = (y % 3 == 0) ? 0.80 : 1.0;
			for (int x = 0; x < w; x++) {
				double dx = (x - cx) / rmax, dy = (y - cy) / rmax;
				double f = scan * (1.0 - 0.5 * (dx * dx + dy * dy));
				int rgb = img.getRGB(x, y);
				img.setRGB(x, y, (clamp((int) (((rgb >> 16) & 255) * f)) << 16)
						| (clamp((int) (((rgb >> 8) & 255) * f)) << 8) | clamp((int) ((rgb & 255) * f)));
			}
		}
	}

	// ---- composition -------------------------------------------------------

	/** Lay panels (possibly different sizes) into a titled 2-column grid. */
	static BufferedImage grid2(BufferedImage[] panels, String[] labels, String title) {
		int cols = 2, pad = 18, gap = 16, lab = 22, top = 30;
		int cw = 0, ch = 0;
		for (BufferedImage p : panels) {
			cw = Math.max(cw, p.getWidth());
			ch = Math.max(ch, p.getHeight());
		}
		int rows = (panels.length + cols - 1) / cols;
		int W = pad * 2 + cols * cw + (cols - 1) * gap;
		int H = top + pad + rows * (ch + lab) + (rows - 1) * gap + pad;
		BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(new Color(16, 16, 20));
		g.fillRect(0, 0, W, H);
		g.setColor(new Color(235, 238, 245));
		g.setFont(new Font("SansSerif", Font.BOLD, 17));
		g.drawString(title, pad, 21);
		g.setFont(new Font("SansSerif", Font.BOLD, 14));
		for (int i = 0; i < panels.length; i++) {
			int cx = pad + (i % cols) * (cw + gap);
			int cy = top + pad + (i / cols) * (ch + lab + gap);
			g.setColor(new Color(200, 205, 216));
			g.drawString(labels[i], cx, cy + 15);
			g.drawImage(panels[i], cx, cy + lab, null);
		}
		g.dispose();
		return out;
	}

	// ---- pixel helpers -----------------------------------------------------

	static BufferedImage upscale(int[][] a, int aw, int ah, int scale) {
		BufferedImage img = new BufferedImage(aw * scale, ah * scale, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < aw; x++) {
			for (int y = 0; y < ah; y++) {
				stamp(img, scale, x, y, a[x][y]);
			}
		}
		return img;
	}

	static void stamp(BufferedImage img, int scale, int ax, int ay, int rgb) {
		int x0 = ax * scale, y0 = ay * scale;
		if (x0 < 0 || y0 < 0 || x0 + scale > img.getWidth() || y0 + scale > img.getHeight()) {
			return;
		}
		for (int sy = 0; sy < scale; sy++) {
			for (int sx = 0; sx < scale; sx++) {
				img.setRGB(x0 + sx, y0 + sy, rgb);
			}
		}
	}

	static void blendBlock(BufferedImage img, int scale, int ax, int ay, int rgb, double t) {
		for (int sy = 0; sy < scale; sy++) {
			for (int sx = 0; sx < scale; sx++) {
				blend(img, ax * scale + sx, ay * scale + sy, rgb, t);
			}
		}
	}

	static void blend(BufferedImage img, int x, int y, int rgb, double t) {
		if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
			return;
		}
		int o = img.getRGB(x, y);
		int r = (int) (((o >> 16) & 255) * (1 - t) + ((rgb >> 16) & 255) * t);
		int g = (int) (((o >> 8) & 255) * (1 - t) + ((rgb >> 8) & 255) * t);
		int b = (int) ((o & 255) * (1 - t) + (rgb & 255) * t);
		img.setRGB(x, y, (r << 16) | (g << 8) | b);
	}

	static int mix(int a, int b, double t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	static int shade(int rgb, double f) {
		return (clamp((int) (((rgb >> 16) & 255) * f)) << 16)
				| (clamp((int) (((rgb >> 8) & 255) * f)) << 8) | clamp((int) ((rgb & 255) * f));
	}

	static int mixWhite(int rgb, double t) {
		return mix(rgb, 0xffffff, t);
	}

	static double pct(double[] sorted, double p) {
		return sorted[Math.min(sorted.length - 1, (int) (p * sorted.length))];
	}

	static int clamp(int v) {
		return v < 0 ? 0 : (v > 255 ? 255 : v);
	}

	static double hash(int x, int y, int s) {
		int h = x * 374761393 + y * 668265263 + s * 2147483647;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= h >>> 16;
		return (h & 0x7fffffff) / (double) 0x7fffffff;
	}

	static double smooth(double t) {
		return t * t * (3 - 2 * t);
	}

	static double vnoise(double fx, double fy, int s) {
		int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
		double tx = smooth(fx - x0), ty = smooth(fy - y0);
		double a = hash(x0, y0, s), b = hash(x0 + 1, y0, s);
		double c = hash(x0, y0 + 1, s), d = hash(x0 + 1, y0 + 1, s);
		return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
	}

	static double fbm(double fx, double fy, int oct, int s) {
		double sum = 0, amp = 0.5, tot = 0;
		for (int i = 0; i < oct; i++) {
			sum += amp * vnoise(fx, fy, s + i);
			tot += amp;
			fx *= 2;
			fy *= 2;
			amp *= 0.5;
		}
		return sum / tot;
	}

	static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}
}
