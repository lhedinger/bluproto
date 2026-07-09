package net.hedinger.prototype.engine;

import static net.hedinger.prototype.engine.ResourceManager.tileSize;

import static java.lang.Math.round;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public class Utils {

	public static float scaleZ(int entityLevel, int camLevel) {
		float scale = tileSize;
		if (camLevel != entityLevel) {
			float zk = camLevel - entityLevel + 1;
			scale = (float) (scale / Math.pow(zk, 0.05));
		}
		return scale;
	}

	public static float scaleZ2(int entityLevel, int camLevel, int size) {
		float scale = size;
		if (camLevel != entityLevel) {
			float zk = camLevel - entityLevel + 1;
			scale = (float) (scale / Math.pow(zk, 0.05));
		}
		return scale;
	}

	public static float toTile(int pixel, int entityLevel, int camLevel) {
		float temp = pixel;
		return temp / scaleZ(entityLevel, camLevel);
	}

	public static int toPixel(double pos, int entityLevel, int camLevel) {
		return (int) round(pos * scaleZ(entityLevel, camLevel));
	}

	public static int toPixel(float pos, int entityLevel, int camLevel) {
		return round(pos * scaleZ(entityLevel, camLevel));
	}

	public static int pixelX(Graphics g, View v, double x, double z, float pixelOffset) {
		int width = (int) g.getClipBounds().getMaxX();
		return Utils.toPixelShift(pixelOffset, (float) x, (int) z, v.getCamX(), v.getCamZ(), width);
	}

	public static int pixelY(Graphics g, View v, double y, double z, float pixelOffset) {
		int width = (int) g.getClipBounds().getMaxY();
		return Utils.toPixelShift(pixelOffset, (float) y, (int) z, v.getCamY(), v.getCamZ(), width);
	}

	public static int pixelX(Graphics g, View v, float pixelOffset) {
		int width = (int) g.getClipBounds().getMaxX();
		return Utils.toPixelShift(pixelOffset, v.getMouseX(), v.getCamZ(), v.getCamX(), v.getCamZ(), width);
	}

	public static int pixelY(Graphics g, View v, float pixelOffset) {
		int width = (int) g.getClipBounds().getMaxY();
		return Utils.toPixelShift(pixelOffset, v.getMouseY(), v.getCamZ(), v.getCamY(), v.getCamZ(), width);
	}

	public static int toPixelShift(float pixelOffset, float entityXY, int entityLevel, float camXY, int camLevel,
			int windowWidth) {
		float ts = scaleZ(entityLevel, camLevel);

		return round((entityXY - camXY) * ts + windowWidth / 2f - pixelOffset);
	}

	public static BufferedImage resize(BufferedImage img, int aWidth, int aHeight) {
		if (img == null) {
			return null;
		}
		int width = img.getWidth();
		int height = img.getHeight();
		BufferedImage dimg = new BufferedImage(aWidth, aHeight, img.getType());
		Graphics2D g = dimg.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(img, 0, 0, aWidth, aHeight, 0, 0, width, height, null);
		g.dispose();
		return dimg;
	}

	public static int divCeil(int value, int divisor) {
		return (int) Math.ceil((float) value / divisor);
	}

	// ------------------------------------------------------------------
	// Seeded random number source. All simulation randomness routes through
	// here so that a given seed reproduces the same world generation and
	// initial population. Call seed(long) before generating a world; read it
	// back with getSeed() to save/restore a run.
	// ------------------------------------------------------------------
	private static long seed = System.nanoTime();
	private static java.util.Random rng = new java.util.Random(seed);

	public static void seed(long s) {
		seed = s;
		rng = new java.util.Random(s);
	}

	public static long getSeed() {
		return seed;
	}

	/** Drop-in replacement for Math.random(): uniform double in [0, 1). */
	public static double random() {
		return rng.nextDouble();
	}

	public static int random(int range) {
		return (int) Math.floor(rng.nextDouble() * range);
	}

	/**
	 * Smooth value noise in [0, 1] sampled at (x, y) -- coherent blobs whose
	 * size is ~1/frequency. It is a pure function of the coordinates and the
	 * current seed and consumes no RNG state, so it can paint environment
	 * fields (fertility, temperature, ...) without disturbing the reproducible
	 * order in which world generation draws from the RNG.
	 */
	public static double noise2(double x, double y, double frequency) {
		double fx = x * frequency;
		double fy = y * frequency;
		int x0 = (int) Math.floor(fx);
		int y0 = (int) Math.floor(fy);
		double tx = smooth(fx - x0);
		double ty = smooth(fy - y0);
		double top = lerp(lattice(x0, y0), lattice(x0 + 1, y0), tx);
		double bot = lerp(lattice(x0, y0 + 1), lattice(x0 + 1, y0 + 1), tx);
		return lerp(top, bot, ty);
	}

	/** Deterministic hash of an integer lattice point to [0, 1). */
	private static double lattice(int xi, int yi) {
		long h = seed * 0x9E3779B97F4A7C15L;
		h ^= (xi & 0xFFFFFFFFL) * 0xC2B2AE3D27D4EB4FL;
		h ^= (yi & 0xFFFFFFFFL) * 0x165667B19E3779F9L;
		h ^= (h >>> 33);
		h *= 0xFF51AFD7ED558CCDL;
		h ^= (h >>> 33);
		return (h >>> 11) * (1.0 / (1L << 53));
	}

	private static double smooth(double t) {
		return t * t * (3 - 2 * t); // smoothstep
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	public static int parseInt(String s, int invalid) {
		int i;
		try {
			i = Integer.parseInt(s);
		} catch (Exception e) {
			i = invalid;
		}
		return i;
	}

}
