package net.hedinger.prototype.simtest;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Art-style prototype harness (scratch tool, not a scenario test). Renders the
 * same procedural island (water -> beach -> grass -> dirt -> stone) with a calm,
 * base-dominant terrain texture and noise-dithered transitions, under several
 * alternative palettes -- one full-size image per palette (with a tile grid and
 * sub-tile entities) plus a contact sheet. Pure code, deterministic, no assets.
 */
public class ArtStyleDemo {

	static final int TILE = 12;
	static final int AW = 12 * TILE, AH = 9 * TILE; // 144x108 art-px = 12x9 tiles

	static double[] bnd; // elevation boundaries between the 5 terrains
	static double bw;    // transition half-width

	// Entity "species" colours (would come from a genome); fixed across palettes.
	static final int[] SPECIES = { 0xd8483a, 0xe8c84a, 0x8a56d0, 0x3fb6c8, 0xe08a2a };

	/** A palette = 5 terrain ramps {shadow, base, highlight}: water,sand,grass,dirt,stone. */
	static final String[] NAMES = { "natural", "muted", "arcade", "cold" };
	static final int[][][] PALETTES = {
			{ // natural (calm water)
					{ 0x1a3a60, 0x24568c, 0x3172b0 }, { 0x8a6d3c, 0xb59a5f, 0xdcc48c },
					{ 0x2a4d24, 0x3f7a38, 0x5f9850 }, { 0x40301f, 0x63472e, 0x866543 },
					{ 0x3a3d47, 0x585d69, 0x82879a } },
			{ // muted / earthy
					{ 0x3a5566, 0x4f7488, 0x6b93a5 }, { 0x9a8a63, 0xbcaf88, 0xd6cda6 },
					{ 0x465a3e, 0x647c58, 0x849a72 }, { 0x574636, 0x76624f, 0x927e68 },
					{ 0x545a5e, 0x73787f, 0x969ba0 } },
			{ // arcade / vivid
					{ 0x143caa, 0x2f6fe0, 0x55a0ff }, { 0xc89028, 0xf0c040, 0xffe486 },
					{ 0x1c8a1c, 0x34b034, 0x70e060 }, { 0x7a3e18, 0xb0602a, 0xe28c42 },
					{ 0x44506a, 0x6a7aa0, 0x9ab0e0 } },
			{ // cold / dusk
					{ 0x16233f, 0x28406e, 0x44669a }, { 0x6a6f7a, 0x8a90a0, 0xaab2c4 },
					{ 0x2a4a46, 0x3e6e62, 0x5c8e7c }, { 0x3a3a4a, 0x545468, 0x707088 },
					{ 0x2c2e3a, 0x474b5c, 0x6a6e84 } },
	};

	public static void main(String[] args) throws Exception {
		double[][] elev = new double[AW][AH];
		double[] flat = new double[AW * AH];
		double min = 1e9, max = -1e9;
		for (int x = 0; x < AW; x++) {
			for (int y = 0; y < AH; y++) {
				double e = islandElevation(x, y);
				elev[x][y] = e;
				flat[x * AH + y] = e;
				min = Math.min(min, e);
				max = Math.max(max, e);
			}
		}
		double[] sorted = flat.clone();
		Arrays.sort(sorted);
		bnd = new double[] { pct(sorted, 0.30), pct(sorted, 0.38), pct(sorted, 0.74), pct(sorted, 0.89) };
		bw = (max - min) * 0.028;

		List<int[]> spots = entitySpots(elev);
		new File("out").mkdirs();

		// One full-size image per palette.
		BufferedImage[] big = new BufferedImage[NAMES.length];
		for (int i = 0; i < NAMES.length; i++) {
			BufferedImage panel = upscale(renderGround(PALETTES[i], elev), 6);
			drawGrid(panel, 6);
			for (int[] sp : spots) {
				drawEntity(panel, 6, sp[0] * TILE + TILE / 2, sp[1] * TILE + TILE / 2, sp[3], SPECIES[sp[2]]);
			}
			big[i] = labelled(panel, "Palette: " + NAMES[i] + "  (calm texture, grid, sub-tile entities)");
			ImageIO.write(big[i], "png", new File("out/art_" + NAMES[i] + ".png"));
		}

		// Contact sheet: all palettes at a smaller scale, 2x2.
		int cols = 2, pad = 16, gap = 16, lab = 24;
		BufferedImage[] small = new BufferedImage[NAMES.length];
		for (int i = 0; i < NAMES.length; i++) {
			BufferedImage panel = upscale(renderGround(PALETTES[i], elev), 4);
			drawGrid(panel, 4);
			for (int[] sp : spots) {
				drawEntity(panel, 4, sp[0] * TILE + TILE / 2, sp[1] * TILE + TILE / 2, sp[3], SPECIES[sp[2]]);
			}
			small[i] = panel;
		}
		int pw = small[0].getWidth(), ph = small[0].getHeight();
		int rows = (NAMES.length + cols - 1) / cols;
		int W = pad * 2 + cols * pw + (cols - 1) * gap;
		int H = pad * 2 + rows * (ph + lab) + (rows - 1) * gap;
		BufferedImage sheet = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		g.setColor(new Color(16, 16, 20));
		g.fillRect(0, 0, W, H);
		g.setFont(new Font("SansSerif", Font.BOLD, 15));
		for (int i = 0; i < NAMES.length; i++) {
			int cx = pad + (i % cols) * (pw + gap);
			int cy = pad + (i / cols) * (ph + lab + gap);
			g.setColor(new Color(210, 214, 224));
			g.drawString(NAMES[i], cx, cy + 17);
			g.drawImage(small[i], cx, cy + lab, null);
		}
		g.dispose();
		ImageIO.write(sheet, "png", new File("out/art_palettes.png"));
		System.out.println("wrote out/art_palettes.png + per-palette images");
	}

	// ---- terrain rendering -------------------------------------------------

	/** Calm, base-dominant intra-terrain texture + noise-dithered transitions. */
	static int[][] renderGround(int[][] pal, double[][] elev) {
		int[][] a = new int[AW][AH];
		for (int x = 0; x < AW; x++) {
			for (int y = 0; y < AH; y++) {
				double e = elev[x][y];
				int terr = bandOf(e);
				for (int i = 0; i < bnd.length; i++) {
					if (Math.abs(e - bnd[i]) < bw) {
						double t = (e - (bnd[i] - bw)) / (2 * bw);
						terr = hash(x, y, 7) < t ? i + 1 : i;
						break;
					}
				}
				// Low-frequency, base-dominant shading: most pixels are the base
				// colour, with occasional large soft shadow/highlight patches.
				double f = fbm(x * 0.12, y * 0.12, 2, 91);
				int idx = f < 0.30 ? 0 : (f > 0.82 ? 2 : 1);
				a[x][y] = pal[terr][idx];
			}
		}
		return a;
	}

	static double islandElevation(double x, double y) {
		double nx = x / AW, ny = y / AH, cx = nx - 0.5, cy = ny - 0.5;
		double dist = Math.sqrt(cx * cx + cy * cy) * 1.85;
		return fbm(x * 0.055, y * 0.055, 5, 1) * 0.72 + (1.0 - dist) * 0.6;
	}

	static int bandOf(double e) {
		int k = 0;
		for (double b : bnd) {
			if (e >= b) {
				k++;
			}
		}
		return k;
	}

	// ---- overlays ----------------------------------------------------------

	static BufferedImage labelled(BufferedImage panel, String text) {
		int lab = 26;
		BufferedImage out = new BufferedImage(panel.getWidth(), panel.getHeight() + lab, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(new Color(16, 16, 20));
		g.fillRect(0, 0, out.getWidth(), out.getHeight());
		g.setFont(new Font("SansSerif", Font.BOLD, 16));
		g.setColor(new Color(214, 218, 228));
		g.drawString(text, 6, 18);
		g.drawImage(panel, 0, lab, null);
		g.dispose();
		return out;
	}

	static void drawGrid(BufferedImage img, int scale) {
		int step = TILE * scale, w = img.getWidth(), h = img.getHeight();
		for (int x = 0; x <= w; x += step) {
			for (int y = 0; y < h; y++) {
				blend(img, x, y, 0x000000, 0.26);
			}
		}
		for (int y = 0; y <= h; y += step) {
			for (int x = 0; x < w; x++) {
				blend(img, x, y, 0x000000, 0.26);
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
			stamp(img, scale, cxArt - 1, cyArt - 1, 0xf4f6ff);
			stamp(img, scale, cxArt + 1, cyArt - 1, 0xf4f6ff);
		}
	}

	static List<int[]> entitySpots(double[][] elev) {
		List<int[]> land = new ArrayList<int[]>();
		for (int tc = 1; tc < AW / TILE - 1; tc++) {
			for (int tr = 1; tr < AH / TILE - 1; tr++) {
				int b = bandOf(elev[tc * TILE + TILE / 2][tr * TILE + TILE / 2]);
				if (b == 2 || b == 3) {
					land.add(new int[] { tc, tr });
				}
			}
		}
		int[] radii = { 1, 1, 2, 1, 2, 1, 3, 1, 2 };
		List<int[]> out = new ArrayList<int[]>();
		for (int k = 0; k < radii.length && !land.isEmpty(); k++) {
			int[] t = land.get((k * 37 + 5) % land.size());
			out.add(new int[] { t[0], t[1], k % SPECIES.length, radii[k] });
		}
		return out;
	}

	// ---- helpers -----------------------------------------------------------

	static BufferedImage upscale(int[][] a, int scale) {
		BufferedImage img = new BufferedImage(AW * scale, AH * scale, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < AW; x++) {
			for (int y = 0; y < AH; y++) {
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

	static int shade(int rgb, double f) {
		return (clamp((int) (((rgb >> 16) & 255) * f)) << 16)
				| (clamp((int) (((rgb >> 8) & 255) * f)) << 8) | clamp((int) ((rgb & 255) * f));
	}

	static int mixWhite(int rgb, double t) {
		int r = (int) (((rgb >> 16) & 255) * (1 - t) + 255 * t);
		int g = (int) (((rgb >> 8) & 255) * (1 - t) + 255 * t);
		int b = (int) ((rgb & 255) * (1 - t) + 255 * t);
		return (r << 16) | (g << 8) | b;
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
