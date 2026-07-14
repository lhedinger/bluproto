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
 * same procedurally generated island (water -> beach -> grass -> dirt -> stone)
 * in several styles so we can compare a smooth high-res baseline against low-res
 * palette + dithered + CRT looks. Pure code, deterministic, no assets -- the
 * point is to see which style makes messy procedural transitions read as
 * intentional texture. Writes out/artstyles.png (6-variant grid) and
 * out/artstyles_ref.png (the two strongest styles, larger, with a tile grid
 * overlay and reference entities).
 */
public class ArtStyleDemo {

	// Art-pixel field size, tile size (art-px), and on-screen scale.
	static final int TILE = 12;
	static final int AW = 12 * TILE, AH = 9 * TILE, SCALE = 4; // 144x108 art-px = 12x9 tiles
	static final int PW = AW * SCALE, PH = AH * SCALE;

	// Terrain ramps: {shadow, base, highlight}. One shared palette across terrains.
	static final int[] WATER = { 0x122f57, 0x1e5aa0, 0x3f86c8 };
	static final int[] SAND = { 0x8a6d3c, 0xbe9d5f, 0xe6cd92 };
	static final int[] GRASS = { 0x23491f, 0x3d7a38, 0x69a955 };
	static final int[] DIRT = { 0x40301f, 0x66492f, 0x8b6944 };
	static final int[] STONE = { 0x343744, 0x565b69, 0x898f9f };
	static final int[][] TERR = { WATER, SAND, GRASS, DIRT, STONE };

	static double[] bnd; // elevation boundaries between the 5 terrains
	static double bw;    // transition half-width (elevation units)

	static final int[][] BAYER = {
			{ 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };

	enum V {
		BASELINE("1. Smooth hi-res, hard edges"),
		PIXEL("2. Low-res palette, hard edges"),
		BAYER_D("3. + ordered (Bayer) dither"),
		NOISE_D("4. + noise dither"),
		FULL("5. + palette-ramp texture"),
		CRT("6. Full + CRT (scanlines/vignette)");

		final String label;
		V(String l) { label = l; }
	}

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

		new File("out").mkdirs();
		ImageIO.write(grid6(elev), "png", new File("out/artstyles.png"));
		ImageIO.write(reference(elev), "png", new File("out/artstyles_ref.png"));
		System.out.println("wrote out/artstyles.png + out/artstyles_ref.png");
	}

	/** The original 6-variant comparison grid. */
	static BufferedImage grid6(double[][] elev) {
		V[] variants = V.values();
		int cols = 3, rows = 2, pad = 18, lab = 26, gap = 16;
		int W = pad * 2 + cols * PW + (cols - 1) * gap;
		int H = pad * 2 + rows * (PH + lab) + (rows - 1) * gap;
		BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(new Color(16, 16, 20));
		g.fillRect(0, 0, W, H);
		g.setFont(new Font("SansSerif", Font.BOLD, 15));
		for (int i = 0; i < variants.length; i++) {
			int cx = pad + (i % cols) * (PW + gap);
			int cy = pad + (i / cols) * (PH + lab + gap);
			g.setColor(new Color(210, 214, 224));
			g.drawString(variants[i].label, cx, cy + 18);
			BufferedImage p = variants[i] == V.BASELINE ? baseline() : upscale(renderArt(variants[i], elev), SCALE);
			if (variants[i] == V.CRT) {
				crt(p);
			}
			g.drawImage(p, cx, cy + lab, null);
		}
		g.dispose();
		return out;
	}

	/** FULL and CRT, larger, with a tile-grid overlay and reference entities. */
	static BufferedImage reference(double[][] elev) {
		int s = 6, w = AW * s, h = AH * s;
		int pad = 18, lab = 26, gap = 18;
		List<int[]> spots = entitySpots(elev); // {tileCol, tileRow, colorIndex}
		int[] genomeColors = { 0xd8483a, 0xe8c84a, 0x8a56d0 }; // three "species"

		BufferedImage[] panels = new BufferedImage[2];
		String[] labels = { "5. Full — tile grid + entities", "6. Full + CRT — tile grid + entities" };
		V[] vs = { V.FULL, V.CRT };
		for (int k = 0; k < 2; k++) {
			BufferedImage p = upscale(renderArt(V.FULL, elev), s);
			drawGrid(p, s);
			for (int[] sp : spots) {
				drawEntity(p, s, sp[0] * TILE + TILE / 2, sp[1] * TILE + TILE / 2, genomeColors[sp[2]]);
			}
			if (vs[k] == V.CRT) {
				crt(p);
			}
			panels[k] = p;
		}
		int W = pad * 2 + 2 * w + gap, H = pad * 2 + lab + h;
		BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(new Color(16, 16, 20));
		g.fillRect(0, 0, W, H);
		g.setFont(new Font("SansSerif", Font.BOLD, 16));
		for (int k = 0; k < 2; k++) {
			int cx = pad + k * (w + gap);
			g.setColor(new Color(210, 214, 224));
			g.drawString(labels[k], cx, pad + 18);
			g.drawImage(panels[k], cx, pad + lab, null);
		}
		g.dispose();
		return out;
	}

	// ---- per-art-pixel terrain colour --------------------------------------

	static int[][] renderArt(V v, double[][] elev) {
		int[][] a = new int[AW][AH];
		boolean dither = v == V.BAYER_D || v == V.NOISE_D || v == V.FULL || v == V.CRT;
		boolean ramp = v == V.FULL || v == V.CRT;
		for (int x = 0; x < AW; x++) {
			for (int y = 0; y < AH; y++) {
				double e = elev[x][y];
				int terr = bandOf(e);
				if (dither) {
					for (int i = 0; i < bnd.length; i++) {
						if (Math.abs(e - bnd[i]) < bw) {
							double t = (e - (bnd[i] - bw)) / (2 * bw);
							double d = (v == V.BAYER_D) ? BAYER[y & 3][x & 3] / 16.0 : hash(x, y, 7);
							terr = d < t ? i + 1 : i;
							break;
						}
					}
				}
				if (ramp) {
					double f = fbm(x * 0.34, y * 0.34, 3, 91);
					a[x][y] = TERR[terr][f < 0.42 ? 0 : (f < 0.72 ? 1 : 2)];
				} else {
					a[x][y] = TERR[terr][1];
				}
			}
		}
		return a;
	}

	static BufferedImage baseline() {
		BufferedImage img = new BufferedImage(PW, PH, BufferedImage.TYPE_INT_RGB);
		for (int dy = 0; dy < PH; dy++) {
			for (int dx = 0; dx < PW; dx++) {
				double e = islandElevation(dx / (double) SCALE, dy / (double) SCALE);
				img.setRGB(dx, dy, TERR[bandOf(e)][1]);
			}
		}
		return img;
	}

	// ---- overlays ----------------------------------------------------------

	/** Thin tile-boundary grid, blended so it reads without hiding the art. */
	static void drawGrid(BufferedImage img, int scale) {
		int step = TILE * scale, w = img.getWidth(), h = img.getHeight();
		for (int x = 0; x <= w; x += step) {
			for (int y = 0; y < h; y++) {
				blend(img, x, y, 0x000000, 0.28);
				blend(img, x, y, 0xffffff, 0.06);
			}
		}
		for (int y = 0; y <= h; y += step) {
			for (int x = 0; x < w; x++) {
				blend(img, x, y, 0x000000, 0.28);
				blend(img, x, y, 0xffffff, 0.06);
			}
		}
	}

	/** A small round critter in the pixel/palette style: shadow, body ramp, eyes. */
	static void drawEntity(BufferedImage img, int scale, int cxArt, int cyArt, int base) {
		int r = 5;
		// ground shadow: a flat dark ellipse under the body
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = 0; dy <= 2; dy++) {
				if ((dx * dx) / (double) (r * r) + (dy * dy) / 2.0 <= 1) {
					blendBlock(img, scale, cxArt + dx, cyArt + r - 1 + dy, 0x000000, 0.30);
				}
			}
		}
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				double d = Math.sqrt(dx * dx + dy * dy);
				if (d > r + 0.3) {
					continue;
				}
				int col;
				if (d > r - 1.0) {
					col = shade(base, 0.30); // outline
				} else {
					double ts = (dy + r) / (2.0 * r);
					col = ts < 0.34 ? mixWhite(base, 0.38) : (ts > 0.7 ? shade(base, 0.6) : base);
				}
				stamp(img, scale, cxArt + dx, cyArt + dy, col);
			}
		}
		// eyes
		stamp(img, scale, cxArt - 2, cyArt - 2, 0xf4f6ff);
		stamp(img, scale, cxArt + 2, cyArt - 2, 0xf4f6ff);
		stamp(img, scale, cxArt - 2, cyArt - 1, 0x101018);
		stamp(img, scale, cxArt + 2, cyArt - 1, 0x101018);
	}

	/** Pick a few grass tiles, spread out, to place reference entities. */
	static List<int[]> entitySpots(double[][] elev) {
		List<int[]> grass = new ArrayList<int[]>();
		for (int tc = 1; tc < AW / TILE - 1; tc++) {
			for (int tr = 1; tr < AH / TILE - 1; tr++) {
				double e = elev[tc * TILE + TILE / 2][tr * TILE + TILE / 2];
				if (bandOf(e) == 2) { // grass
					grass.add(new int[] { tc, tr, 0 });
				}
			}
		}
		List<int[]> out = new ArrayList<int[]>();
		if (!grass.isEmpty()) {
			int[] idx = { grass.size() / 6, grass.size() / 2, (grass.size() * 5) / 6 };
			for (int k = 0; k < 3; k++) {
				int[] s = grass.get(Math.min(grass.size() - 1, idx[k]));
				out.add(new int[] { s[0], s[1], k });
			}
		}
		return out;
	}

	// ---- terrain field / CRT -----------------------------------------------

	static double islandElevation(double x, double y) {
		double nx = x / AW, ny = y / AH;
		double cx = nx - 0.5, cy = ny - 0.5;
		double dist = Math.sqrt(cx * cx + cy * cy) * 1.85;
		double n = fbm(x * 0.055, y * 0.055, 5, 1);
		return n * 0.72 + (1.0 - dist) * 0.6;
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

	static void crt(BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		double cx = w / 2.0, cy = h / 2.0, rmax = Math.sqrt(cx * cx + cy * cy);
		for (int y = 0; y < h; y++) {
			double scan = (y % 3 == 0) ? 0.78 : 1.0;
			for (int x = 0; x < w; x++) {
				double dx = (x - cx) / rmax, dy = (y - cy) / rmax;
				double f = scan * (1.0 - 0.55 * (dx * dx + dy * dy));
				int rgb = img.getRGB(x, y);
				img.setRGB(x, y, (clamp((int) (((rgb >> 16) & 255) * f)) << 16)
						| (clamp((int) (((rgb >> 8) & 255) * f)) << 8) | clamp((int) ((rgb & 255) * f)));
			}
		}
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

	/** Fill the scale×scale display block for one art-pixel. */
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

	/** Blend an art-pixel block toward a colour (used for the ground shadow). */
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
