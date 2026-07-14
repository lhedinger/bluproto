package net.hedinger.prototype.simtest;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

import javax.imageio.ImageIO;

/**
 * Art-style prototype harness (scratch tool, not a scenario test). Renders the
 * same procedurally generated island (water -> beach -> grass -> dirt -> stone)
 * in several styles so we can compare a smooth high-res baseline against low-res
 * palette + dithered + CRT looks. Pure code, deterministic, no assets -- the
 * point is to see which style makes messy procedural transitions read as
 * intentional texture. Run: {@code java ...simtest.ArtStyleDemo} -> out/artstyles.png.
 */
public class ArtStyleDemo {

	// Art-pixel field size and on-screen scale.
	static final int AW = 150, AH = 108, SCALE = 4;
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
		// Precompute the elevation field, and set terrain boundaries at percentiles
		// so the distribution is nice regardless of the noise scale.
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

		V[] variants = V.values();
		int cols = 3, rows = 2, pad = 18, lab = 26, gap = 16;
		int W = pad * 2 + cols * PW + (cols - 1) * gap;
		int H = pad * 2 + rows * (PH + lab) + (rows - 1) * gap;
		BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(16, 16, 20));
		g.fillRect(0, 0, W, H);
		g.setFont(new Font("SansSerif", Font.BOLD, 15));
		for (int i = 0; i < variants.length; i++) {
			int cx = pad + (i % cols) * (PW + gap);
			int cy = pad + (i / cols) * (PH + lab + gap);
			g.setColor(new Color(210, 214, 224));
			g.drawString(variants[i].label, cx, cy + 18);
			g.drawImage(panel(variants[i], elev), cx, cy + lab, null);
		}
		g.dispose();
		new File("out").mkdirs();
		ImageIO.write(out, "png", new File("out/artstyles.png"));
		System.out.println("wrote out/artstyles.png");
	}

	static BufferedImage panel(V v, double[][] elev) {
		BufferedImage img = new BufferedImage(PW, PH, BufferedImage.TYPE_INT_RGB);
		if (v == V.BASELINE) {
			// Full display resolution, flat base colour, hard band edges.
			for (int dy = 0; dy < PH; dy++) {
				for (int dx = 0; dx < PW; dx++) {
					double e = islandElevation(dx / (double) SCALE, dy / (double) SCALE);
					img.setRGB(dx, dy, TERR[bandOf(e)][1]);
				}
			}
			return img;
		}
		// Low-res: compute per art-pixel, then upscale into SCALE blocks.
		for (int x = 0; x < AW; x++) {
			for (int y = 0; y < AH; y++) {
				double e = elev[x][y];
				int terr = bandOf(e);
				boolean dither = v == V.BAYER_D || v == V.NOISE_D || v == V.FULL || v == V.CRT;
				if (dither) {
					for (int i = 0; i < bnd.length; i++) {
						if (Math.abs(e - bnd[i]) < bw) {
							double t = (e - (bnd[i] - bw)) / (2 * bw); // 0..1 toward hi
							double d = (v == V.BAYER_D) ? BAYER[y & 3][x & 3] / 16.0 : hash(x, y, 7);
							terr = d < t ? i + 1 : i;
							break;
						}
					}
				}
				int col;
				if (v == V.FULL || v == V.CRT) {
					double f = fbm(x * 0.34, y * 0.34, 3, 91);
					int idx = f < 0.42 ? 0 : (f < 0.72 ? 1 : 2);
					col = TERR[terr][idx];
				} else {
					col = TERR[terr][1];
				}
				fill(img, x, y, col);
			}
		}
		if (v == V.CRT) {
			crt(img);
		}
		return img;
	}

	// ---- terrain field -----------------------------------------------------

	static double islandElevation(double x, double y) {
		double nx = x / AW, ny = y / AH;
		double cx = nx - 0.5, cy = ny - 0.5;
		double dist = Math.sqrt(cx * cx + cy * cy) * 1.85;
		double n = fbm(x * 0.055, y * 0.055, 5, 1);
		double e = n * 0.72 + (1.0 - dist) * 0.6;
		return e;
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

	// ---- CRT post ----------------------------------------------------------

	static void crt(BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		double cx = w / 2.0, cy = h / 2.0, rmax = Math.sqrt(cx * cx + cy * cy);
		for (int y = 0; y < h; y++) {
			double scan = (y % 3 == 0) ? 0.78 : 1.0; // scanline every 3rd display row
			for (int x = 0; x < w; x++) {
				double dx = (x - cx) / rmax, dy = (y - cy) / rmax;
				double vig = 1.0 - 0.55 * (dx * dx + dy * dy); // corner darkening
				double f = scan * vig;
				int rgb = img.getRGB(x, y);
				int r = clamp((int) (((rgb >> 16) & 255) * f));
				int gg = clamp((int) (((rgb >> 8) & 255) * f));
				int b = clamp((int) ((rgb & 255) * f));
				img.setRGB(x, y, (r << 16) | (gg << 8) | b);
			}
		}
	}

	// ---- helpers -----------------------------------------------------------

	static void fill(BufferedImage img, int ax, int ay, int rgb) {
		for (int sy = 0; sy < SCALE; sy++) {
			for (int sx = 0; sx < SCALE; sx++) {
				img.setRGB(ax * SCALE + sx, ay * SCALE + sy, rgb);
			}
		}
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
