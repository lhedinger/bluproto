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

		System.out.println("wrote out/art_resolution.png, art_texture.png, art_crt.png");
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
