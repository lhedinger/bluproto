package net.hedinger.prototype.engine;

import java.awt.image.BufferedImage;

/**
 * Global render-style toggles and post-processing. Kept separate so the pixel
 * ground and the CRT overlay can be flipped at runtime (or per snapshot) without
 * threading a config object through the whole render path.
 */
public final class RenderFx {

	/** Low-res palette + dithered pixel ground (vs the smooth GroundTextures). */
	public static boolean pixelGround = true;
	/** CRT post-process (scanlines + vignette) over the finished frame. */
	public static boolean crt = false;
	/** Draw the scenario debug overlay (heading arrows, labels, glyphs). */
	public static boolean debugOverlay = true;
	/** Draw the decorative tall grass and shrubs on fertile tiles. Off for
	 *  behaviour-test rendering, where they would only clutter the subject. */
	public static boolean foliage = true;
	/** Draw the live tall-grass tufts (the blades that part around walkers).
	 *  The desktop view keeps them; the server's chunk bake turns them off,
	 *  because they read live vegetation — the web client shows growth and
	 *  depletion through its vegetation sprite layer instead, and a bake must
	 *  be a pure function of the terrain. */
	public static boolean grassTufts = true;
	/** Memoise rendered creature sprites (vs redrawing every entity each frame). */
	public static boolean cacheSprites = true;
	/** Re-stamp foliage over creatures standing in walkable sight-blockers
	 * (thicket cover, reed beds), so hidden creatures render part-hidden. */
	public static boolean concealFoliage = true;
	/** Render holes as translucent pits, so the layer beneath shows through. */
	public static boolean holeTranslucent = true;
	/** How far a pit's interior washes back into its own dark rather than
	 * showing the floor below (0 fully see-through .. 1 solid dark). At 0.7 a
	 * pit is 30% transparent: enough to tell there is a floor down there and
	 * what it is made of, while still reading as a hole rather than a window.
	 * Only pits with a level under them show anything at all — see
	 * {@code Grid.pitFloor}. */
	public static double holeDepth = 0.7;

	private RenderFx() {
	}

	/** Scanline + vignette pass over a finished frame; a toggleable overlay. */
	public static void crt(BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		double cx = w / 2.0, cy = h / 2.0, rmax = Math.sqrt(cx * cx + cy * cy);
		for (int y = 0; y < h; y++) {
			double scan = (y % 3 == 0) ? 0.82 : 1.0;
			for (int x = 0; x < w; x++) {
				double dx = (x - cx) / rmax, dy = (y - cy) / rmax;
				double f = scan * (1.0 - 0.45 * (dx * dx + dy * dy));
				int rgb = img.getRGB(x, y);
				img.setRGB(x, y, (cl((int) (((rgb >> 16) & 255) * f)) << 16)
						| (cl((int) (((rgb >> 8) & 255) * f)) << 8) | cl((int) ((rgb & 255) * f)));
			}
		}
	}

	private static int cl(int v) {
		return v < 0 ? 0 : (v > 255 ? 255 : v);
	}
}
