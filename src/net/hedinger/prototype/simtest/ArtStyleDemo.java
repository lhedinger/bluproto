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

		System.out.println("wrote art_resolution/texture/crt.png + art_entities/population.png");
	}

	// ---- creative entities -------------------------------------------------

	static final int[] ECOLORS = {
			0xd8483a, 0xe8a53a, 0xe8c84a, 0x8fbf3a, 0x3fb36a, 0x3fb6c8,
			0x4a86d8, 0x8a56d0, 0xd05fb0, 0xb0836a, 0x9aa0ad, 0xdfe2ea };

	/** A procedural critter: silhouette + features, sized in art-px. */
	static class Critter {
		int r, color, shape, eyes, top, pattern, tail;
		boolean feet, outline;

		Critter(int r, int color, int shape, int eyes, int top, int pattern, boolean feet, int tail) {
			this.r = r;
			this.color = color;
			this.shape = shape;
			this.eyes = eyes;
			this.top = top;
			this.pattern = pattern;
			this.feet = feet;
			this.tail = tail;
			this.outline = true;
		}
	}

	/** shape: 0 round 1 tall 2 wide 3 diamond 4 two-lobed 5 rounded-square. */
	static boolean inside(int shape, double dx, double dy, int r) {
		switch (shape) {
		case 1:
			return sq(dx / (r - 0.5)) + sq(dy / (r + 0.8)) <= 1.08;
		case 2:
			return sq(dx / (r + 0.8)) + sq(dy / (r - 0.5)) <= 1.08;
		case 3:
			return Math.abs(dx) + Math.abs(dy) <= r + 0.4;
		case 4:
			double o = Math.max(1, r - 1);
			return Math.min(sq(dx - o) + dy * dy, sq(dx + o) + dy * dy) <= sq(r - 0.4);
		case 5:
			return Math.pow(Math.abs(dx) / r, 3) + Math.pow(Math.abs(dy) / r, 3) <= 1.0;
		default:
			return dx * dx + dy * dy <= r * r + r * 0.6;
		}
	}

	static double sq(double v) {
		return v * v;
	}

	static boolean edge(int shape, int dx, int dy, int r) {
		return inside(shape, dx, dy, r) && (!inside(shape, dx + 1, dy, r) || !inside(shape, dx - 1, dy, r)
				|| !inside(shape, dx, dy + 1, r) || !inside(shape, dx, dy - 1, r));
	}

	static void drawCritter(BufferedImage img, int scale, int cx, int cy, Critter c) {
		int r = c.r;
		// ground shadow
		for (int dx = -r; dx <= r; dx++) {
			if (dx * dx <= r * r) {
				blendBlock(img, scale, cx + dx, cy + r + 1, 0x000000, 0.26);
			}
		}
		// body
		for (int dx = -r - 1; dx <= r + 1; dx++) {
			for (int dy = -r - 1; dy <= r + 1; dy++) {
				if (!inside(c.shape, dx, dy, r)) {
					continue;
				}
				int col;
				if (c.outline && edge(c.shape, dx, dy, r)) {
					col = shade(c.color, 0.34);
				} else {
					double ts = (dy + r) / (2.0 * r);
					col = ts < 0.4 ? mixWhite(c.color, 0.42) : (ts > 0.72 ? shade(c.color, 0.6) : c.color);
					if (c.pattern == 1 && dy >= 1) {
						col = mixWhite(col, 0.22); // pale belly
					} else if (c.pattern == 2 && Math.abs(dx) <= 0 && Math.abs(dy) <= 0) {
						col = shade(c.color, 0.5); // centre spot
					} else if (c.pattern == 3 && dx == 0) {
						col = shade(col, 0.72); // back stripe
					}
				}
				stamp(img, scale, cx + dx, cy + dy, col);
			}
		}
		// eyes
		int ex = Math.max(1, r / 2);
		if (c.eyes == 2) {
			eye(img, scale, cx - ex, cy - 1, r);
			eye(img, scale, cx + ex, cy - 1, r);
		} else if (c.eyes == 1) {
			eye(img, scale, cx, cy - 1, r);
		}
		// top feature
		int ty = cy - r - 1;
		switch (c.top) {
		case 1: // antenna
			stamp(img, scale, cx, ty, shade(c.color, 0.4));
			stamp(img, scale, cx, ty - 1, mixWhite(c.color, 0.5));
			break;
		case 2: // spikes
			stamp(img, scale, cx, ty, shade(c.color, 0.45));
			stamp(img, scale, cx - r + 1, cy - r, shade(c.color, 0.45));
			stamp(img, scale, cx + r - 1, cy - r, shade(c.color, 0.45));
			break;
		case 3: // ears / horns
			stamp(img, scale, cx - r + 1, ty, c.color);
			stamp(img, scale, cx + r - 1, ty, c.color);
			break;
		case 4: // fin / crest
			stamp(img, scale, cx, ty, mixWhite(c.color, 0.3));
			if (r >= 3) {
				stamp(img, scale, cx, ty - 1, mixWhite(c.color, 0.3));
			}
			break;
		default:
			break;
		}
		// tail
		if (c.tail == 1) {
			stamp(img, scale, cx + r + 1, cy, shade(c.color, 0.72));
			if (r >= 3) {
				stamp(img, scale, cx + r + 2, cy, shade(c.color, 0.6));
			}
		}
		// feet
		if (c.feet) {
			stamp(img, scale, cx - (r - 1), cy + r, shade(c.color, 0.45));
			stamp(img, scale, cx + (r - 1), cy + r, shade(c.color, 0.45));
		}
	}

	static void eye(BufferedImage img, int scale, int x, int y, int r) {
		stamp(img, scale, x, y, 0x14141c); // pupil
		if (r >= 3) {
			stamp(img, scale, x, y - 1, 0xf4f6ff); // highlight above
		}
	}

	/** Zoomed grid of varied critters (as they would look at 12 px/tile). */
	static BufferedImage bestiary() {
		int cols = 6, rows = 4, thumb = 13, scale = 9, cell = thumb * scale;
		int pad = 18, gap = 8, top = 30;
		int W = pad * 2 + cols * cell + (cols - 1) * gap;
		int H = top + pad * 2 + rows * cell + (rows - 1) * gap;
		BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(new Color(20, 21, 27));
		g.fillRect(0, 0, W, H);
		g.setColor(new Color(235, 238, 245));
		g.setFont(new Font("SansSerif", Font.BOLD, 17));
		g.drawString("Entity variations - shown as at 12 px/tile (radius 2-3 art-px), zoomed", pad, 21);
		for (int i = 0; i < cols * rows; i++) {
			int r = 2 + (i % 3 == 2 ? 1 : 0);
			Critter c = new Critter(r, ECOLORS[i % ECOLORS.length], i % 6, 1 + (i / 2) % 2,
					i % 5, (i / 3) % 4, i % 3 == 0, (i % 4 == 0) ? 1 : 0);
			BufferedImage t = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_RGB);
			Graphics2D tg = t.createGraphics();
			tg.setColor(new Color(34, 36, 44));
			tg.fillRect(0, 0, cell, cell);
			tg.dispose();
			drawCritter(t, scale, thumb / 2, thumb / 2, c);
			int cx = pad + (i % cols) * (cell + gap);
			int cy = top + pad + (i / cols) * (cell + gap);
			g.drawImage(t, cx, cy, null);
		}
		g.dispose();
		return out;
	}

	/** The natural island populated with a diverse set of critters at true scale. */
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
					Critter c = new Critter(2 + (s % 3 == 0 ? 1 : 0), ECOLORS[(s / 7) % ECOLORS.length],
							s % 6, 1 + (s / 6) % 2, (s / 2) % 5, (s / 4) % 4, s % 3 == 0, (s % 5 == 0) ? 1 : 0);
					drawCritter(p, scale, tc * f.R + f.R / 2, tr * f.R + f.R / 2, c);
					placed++;
				}
			}
		}
		return labelBar(p, "Natural island populated with varied critters (true 12 px/tile scale)");
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
