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
	/** Memoise rendered creature sprites (vs redrawing every entity each frame). */
	public static boolean cacheSprites = true;
	/** Render holes as translucent pits, so the layer beneath shows through. */
	public static boolean holeTranslucent = true;
	/** How opaque the pit shade is over the revealed level (0 clear .. 1 black).
	 * The layer beneath a hole is the real level below (already dimmed by the
	 * per-level depth-fog), so its parallax comes for free from the engine's
	 * per-level projection (Utils.scaleZ) and only a light shade is wanted. */
	public static double holeDepth = 0.2;

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
