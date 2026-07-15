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
		if (args.length > 0 && args[0].equals("actions")) {
			animateActions();
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
		ImageIO.write(actionsSheet(), "png", new File("out/art_actions.png"));

		System.out.println("wrote art_resolution/texture/crt/entities/population/motion/actions.png");
	}

	/** Strip sheet: each generic action across frames, on a different form per row. */
	static BufferedImage actionsSheet() {
		int thumb = 17, scale = 8, cell = thumb * scale, pad = 18, gap = 8, top = 30, labW = 130;
		int cols = 8, rows = ACTION_NAME.length;
		int W = pad + labW + cols * cell + (cols - 1) * gap + pad;
		int H = top + pad + rows * cell + (rows - 1) * (gap + 14) + pad;
		BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(new Color(20, 21, 27));
		g.fillRect(0, 0, W, H);
		g.setColor(new Color(235, 238, 245));
		g.setFont(new Font("SansSerif", Font.BOLD, 17));
		g.drawString("Generic action animations (form-agnostic) - one modifier system on every body", pad, 21);
		g.setFont(new Font("SansSerif", Font.BOLD, 13));
		for (int a = 0; a < rows; a++) {
			int form = a % 6, color = ECOLORS[a % ECOLORS.length];
			int cy = top + pad + a * (cell + gap + 14);
			g.setColor(new Color(200, 205, 216));
			g.drawString(ACTION_NAME[a], pad, cy + cell / 2);
			for (int col = 0; col < cols; col++) {
				double t = col / (double) (cols - 1);
				TD c = new TD(3, color, form, 3, form == 0, 1, 1, false, -Math.PI / 2);
				BufferedImage tImg = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_RGB);
				Graphics2D tg = tImg.createGraphics();
				tg.setColor(new Color(34, 36, 44));
				tg.fillRect(0, 0, cell, cell);
				tg.dispose();
				drawTopdown(tImg, scale, thumb / 2, thumb / 2, c, t * 6.0, actionMod(a, t, color));
				g.drawImage(tImg, pad + labW + col * (cell + gap), cy, null);
			}
		}
		g.dispose();
		return out;
	}

	/** Animated lineup: several forms each looping through the action set. */
	static void animateActions() throws Exception {
		int aw = 220, ah = 60, scale = 6;
		// Flat calm-grass background (force every tile to grass).
		Field f = new Field();
		f.R = 12;
		f.AW = aw;
		f.AH = ah;
		f.bnd = new double[] { -1, -1, 1e9, 1e9 };
		f.elev = new double[aw][ah];
		BufferedImage base = upscale(renderGround(f, NATURAL, 0.6, 1.44, 0.30, 0.82), aw, ah, scale);

		int n = ACTION_NAME.length; // one demonstrator per action
		int frames = 150, period = 150;
		File dir = new File("out/frames");
		dir.mkdirs();
		for (File old : dir.listFiles()) {
			old.delete();
		}
		for (int fr = 0; fr < frames; fr++) {
			BufferedImage img = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.drawImage(base, 0, 0, null);
			g.dispose();
			double t = (fr % period) / (double) period;
			for (int i = 0; i < n; i++) {
				int color = ECOLORS[i % ECOLORS.length], form = i % 6;
				TD c = new TD(3, color, form, 3, form == 0, 1, 1, false, -Math.PI / 2);
				int cx = 16 + i * ((aw - 24) / n);
				drawTopdown(img, scale, cx, ah / 2, c, t * 6.0, actionMod(i, t, color));
			}
			ImageIO.write(img, "png", new File(dir, String.format("f%04d.png", fr)));
		}
		System.out.println("wrote " + frames + " action frames to out/frames/");
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

	/**
	 * A generic, form-agnostic action modifier: a transform envelope over the
	 * draw (squash/stretch, offset, extra rotation, colour tint, dither-dissolve)
	 * plus an optional hovering glyph / expanding ring. The same envelope works on
	 * any silhouette, so one set of actions animates every entity type.
	 */
	static class Mod {
		double sA = 1, sP = 1, offA = 0, offP = 0, rot = 0, tintAmt = 0, dissolve = 0, ringT = -1;
		int tint = 0xffffff, glyph = 0;
	}

	static final Mod IDENTITY = new Mod();

	static void drawTopdown(BufferedImage img, int scale, int cx, int cy, TD c) {
		drawTopdown(img, scale, cx, cy, c, 0, IDENTITY);
	}

	static void drawTopdown(BufferedImage img, int scale, int cx, int cy, TD c, double phase) {
		drawTopdown(img, scale, cx, cy, c, phase, IDENTITY);
	}

	static void drawTopdown(BufferedImage img, int scale, int cx, int cy, TD c, double phase, Mod m) {
		double h = c.heading + m.rot;
		double ux = Math.cos(h), uy = Math.sin(h), rx = -uy, ry = ux;
		int ox = cx + (int) Math.round(m.offA * ux + m.offP * rx);
		int oy = cy + (int) Math.round(m.offA * uy + m.offP * ry);
		int r = c.r, seed = c.color;
		double sA = m.sA, sP = m.sP;
		java.util.HashSet<Integer> body = new java.util.HashSet<Integer>();
		int rng = (int) ((r + 2) * Math.max(sA, sP)) + 1;
		for (double la = -rng; la <= rng; la += 0.5) {
			for (double pe = -rng; pe <= rng; pe += 0.5) {
				if (inForm(c.form, la / sA, pe / sP, r, seed, phase)) {
					int[] w = wpt(la, pe, ux, uy, rx, ry);
					body.add(key(w[0], w[1]));
				}
			}
		}
		// Ambient shadow: faint halo just outside the silhouette (south-biased).
		for (int p : body) {
			int dx = p / 128 - 32, dy = p % 128 - 32;
			if (!body.contains(key(dx, dy + 1))) {
				blendBlock(img, scale, ox + dx, oy + dy + 1, 0x000000, 0.22 * (1 - m.dissolve));
			}
		}
		// Appendages (drawn under the body's edge).
		if (c.form == 0 || c.form == 4) { // bilateral legs, scuttling
			int pairs = c.legs;
			double half = (c.form == 4 ? r - 1 : r - 0.2) * sP, len = (c.form == 4 ? 2.4 : 1.4) * sP;
			for (int i = 0; i < pairs; i++) {
				double la = (pairs == 1 ? 0 : lerp(-r + 0.6, r - 0.6, i / (double) (pairs - 1))) * sA;
				double drive = 0.55 + 0.45 * Math.sin(phase + i * 2.1);
				for (double e = half; e <= half + len * drive; e += 0.7) {
					int[] a = wpt(la, e, ux, uy, rx, ry), b = wpt(la, -e, ux, uy, rx, ry);
					stampMod(img, scale, ox, oy, a[0], a[1], shade(c.color, 0.5), m, seed);
					stampMod(img, scale, ox, oy, b[0], b[1], shade(c.color, 0.5), m, seed);
				}
			}
		} else if (c.form == 1) { // radial cilia, rotating + pulsing
			int n = c.legs * 2 + 5;
			double base = (r + 0.8) * ((sA + sP) / 2) + 0.35 * Math.sin(phase * 1.5);
			for (int i = 0; i < n; i++) {
				double ang = 2 * Math.PI * i / n + phase * 0.25;
				int[] a = wpt(Math.cos(ang) * base, Math.sin(ang) * base, ux, uy, rx, ry);
				stampMod(img, scale, ox, oy, a[0], a[1], shade(c.color, 0.5), m, seed);
			}
		}
		// Body, lit from screen-north (world dy) regardless of heading.
		for (int p : body) {
			int dx = p / 128 - 32, dy = p % 128 - 32;
			double ts = (dy + r) / (2.0 * r);
			int col = ts < 0.4 ? mixWhite(c.color, 0.4) : (ts > 0.72 ? shade(c.color, 0.6) : c.color);
			stampMod(img, scale, ox, oy, dx, dy, col, m, seed);
		}
		if (c.core > 0) {
			stampMod(img, scale, ox, oy, 0, 0, c.core == 1 ? shade(c.color, 0.5) : mixWhite(c.color, 0.55), m, seed);
		}
		// Dorsal stripe along heading, or a lit head at the front.
		if (c.pattern == 1) {
			for (double la = -r; la <= r; la += 0.5) {
				int[] a = wpt(la * sA, 0, ux, uy, rx, ry);
				if (body.contains(key(a[0], a[1]))) {
					stampMod(img, scale, ox, oy, a[0], a[1], shade(c.color, 0.72), m, seed);
				}
			}
		} else if (c.pattern == 2) {
			int[] a = wpt((r - 0.3) * sA, 0, ux, uy, rx, ry);
			if (body.contains(key(a[0], a[1]))) {
				stampMod(img, scale, ox, oy, a[0], a[1], mixWhite(c.color, 0.55), m, seed);
			}
		}
		if (c.antennae) {
			int[] a = wpt((r + 0.9) * sA, 0.9 * sP, ux, uy, rx, ry), b = wpt((r + 0.9) * sA, -0.9 * sP, ux, uy, rx, ry);
			stampMod(img, scale, ox, oy, a[0], a[1], shade(c.color, 0.55), m, seed);
			stampMod(img, scale, ox, oy, b[0], b[1], shade(c.color, 0.55), m, seed);
		}
		if (c.tail) {
			int[] t = wpt(-(r + 0.9) * sA, 0, ux, uy, rx, ry);
			stampMod(img, scale, ox, oy, t[0], t[1], shade(c.color, 0.6), m, seed);
		}
		// Overlays: expanding ring (spawn/sense) and a hovering glyph (screen-north).
		if (m.ringT >= 0) {
			drawRing(img, scale, cx, cy, m.ringT * (r + 5), 1 - m.ringT);
		}
		if (m.glyph > 0) {
			drawGlyph(img, scale, cx, cy - r - 3, m.glyph);
		}
	}

	/** Stamps one entity pixel through the modifier (dither-dissolve + tint). */
	static void stampMod(BufferedImage img, int scale, int ox, int oy, int dx, int dy, int rgb, Mod m, int seed) {
		if (m.dissolve > 0 && hash(dx + 40, dy + 40, seed) < m.dissolve) {
			return;
		}
		stamp(img, scale, ox + dx, oy + dy, m.tintAmt > 0 ? mix(rgb, m.tint, Math.min(1, m.tintAmt)) : rgb);
	}

	// action kinds
	static final int A_IDLE = 0, A_LUNGE = 1, A_HURT = 2, A_EAT = 3, A_COURT = 4, A_ALARM = 5,
			A_DEATH = 6, A_SPAWN = 7;
	static final String[] ACTION_NAME = { "idle", "lunge/attack", "hurt", "eat", "court/mate",
			"alarm/flee", "death", "spawn" };

	/** Maps an action + progress t in [0,1] to a generic modifier envelope. */
	static Mod actionMod(int action, double t, int seed) {
		Mod m = new Mod();
		switch (action) {
		case A_LUNGE:
			if (t < 0.28) { // wind up: squash and pull back
				double u = t / 0.28;
				m.sA = 1 - 0.2 * u;
				m.sP = 1 + 0.16 * u;
				m.offA = -1.0 * u;
			} else if (t < 0.5) { // strike: stretch and lunge forward
				double u = (t - 0.28) / 0.22;
				m.sA = 0.8 + 0.75 * u;
				m.sP = 1.16 - 0.36 * u;
				m.offA = -1.0 + 3.0 * u;
				if (u > 0.75) {
					m.glyph = 1; // impact spark
				}
			} else { // recover
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
			double c = Math.abs(Math.sin(t * Math.PI * 3)); // chomp
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
			m.glyph = 3; // heart
			break;
		}
		case A_ALARM: {
			double j = t < 0.55 ? 1 : Math.max(0, 1 - (t - 0.55) * 2.2);
			m.offP = (hash((int) (t * 60), seed, 6) - 0.5) * 2.4 * j;
			m.sP = 1 - 0.1 * j;
			m.sA = 1 + 0.14 * j;
			m.glyph = 2; // exclamation
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

	static void drawGlyph(BufferedImage img, int scale, int gx, int gy, int type) {
		switch (type) {
		case 1: { // impact spark (light)
			int c = 0xfff2b0;
			stamp(img, scale, gx, gy, c);
			stamp(img, scale, gx - 1, gy, c);
			stamp(img, scale, gx + 1, gy, c);
			stamp(img, scale, gx, gy - 1, c);
			stamp(img, scale, gx, gy + 1, c);
			break;
		}
		case 2: { // exclamation
			int c = 0xffe14a;
			stamp(img, scale, gx, gy - 2, c);
			stamp(img, scale, gx, gy - 1, c);
			stamp(img, scale, gx, gy + 1, c);
			break;
		}
		case 3: { // heart
			int c = 0xff6ab0;
			stamp(img, scale, gx - 1, gy - 1, c);
			stamp(img, scale, gx + 1, gy - 1, c);
			stamp(img, scale, gx - 1, gy, c);
			stamp(img, scale, gx, gy, c);
			stamp(img, scale, gx + 1, gy, c);
			stamp(img, scale, gx, gy + 1, c);
			break;
		}
		default:
			break;
		}
	}

	static void drawRing(BufferedImage img, int scale, int cx, int cy, double radius, double alpha) {
		int n = Math.max(8, (int) (radius * 4));
		for (int i = 0; i < n; i++) {
			double a = 2 * Math.PI * i / n;
			int dx = (int) Math.round(Math.cos(a) * radius), dy = (int) Math.round(Math.sin(a) * radius);
			blendBlock(img, scale, cx + dx, cy + dy, 0xbfe0ff, 0.55 * alpha);
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
