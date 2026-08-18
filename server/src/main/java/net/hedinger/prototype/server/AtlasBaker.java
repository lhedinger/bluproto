package net.hedinger.prototype.server;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import net.hedinger.prototype.engine.ProcCreature;
import net.hedinger.prototype.sim.PhenoRegistry;

/**
 * Bakes a creature phenotype's idle sprites into one PNG atlas — the Phase 4
 * render-split payoff (MODERNIZATION.md): the {@link ProcCreature} art is the
 * single visual truth, rendered server-side and shipped to the browser, never
 * ported. The browser blits cells; it draws no organism itself.
 *
 * <p>Layout: {@link ProcCreature#DIRS} columns (heading buckets) ×
 * {@link ProcCreature#ANIM} rows (idle-walk frames), each a {@link #CELL}px
 * transparent square with the organism centred. The client samples column =
 * heading bucket, row = animation frame — the same quantisation the live sprite
 * cache uses — so the web creature turns and shuffles exactly like the desktop
 * one. Atlases are cached by phenotype key; a handful of species, a handful of
 * bakes.
 */
final class AtlasBaker {

	/** Per-cell pixel box. Sized so the widest form (an elongated body turned
	 *  diagonally) and a flyer's detached shadow fit inside one cell with margin —
	 *  otherwise a neighbouring cell bleeds in and a single extracted frame shows a
	 *  stray fragment. Bigger than strictly needed also keeps upscaling crisp. */
	static final int CELL = 96;

	private static final ConcurrentHashMap<Long, byte[]> CACHE = new ConcurrentHashMap<Long, byte[]>();

	/** PNG bytes for a shape key, or null if the key was never registered. */
	static byte[] atlas(long shapeKey) {
		ProcCreature.Phenotype ph = PhenoRegistry.get(shapeKey);
		if (ph == null) {
			return null;
		}
		return CACHE.computeIfAbsent(shapeKey, k -> bake(ph));
	}

	private static byte[] bake(ProcCreature.Phenotype registered) {
		// Colour-neutral: baked at exact mid-grey so every shade()/mixWhite()
		// tone lands on an invertible grey ramp (pivot 128) the client re-tints
		// per creature. One sheet serves a shape's every colour variant.
		ProcCreature.Phenotype ph = ProcCreature.neutral(registered);
		int dirs = ProcCreature.DIRS, anim = ProcCreature.ANIM;
		BufferedImage atlas = new BufferedImage(dirs * CELL, anim * CELL, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = atlas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// Art radius kept well under CELL/2 so appendages, an elongated body and a
		// flyer's offset shadow all stay inside the cell (no bleed into neighbours).
		double artRadius = CELL * 0.22;
		for (int d = 0; d < dirs; d++) {
			double heading = d * (Math.PI * 2 / dirs);
			for (int a = 0; a < anim; a++) {
				double phase = (a + 0.5) / anim * (Math.PI * 2);
				int cx = d * CELL + CELL / 2;
				int cy = a * CELL + CELL / 2;
				ProcCreature.draw(g, cx, cy, artRadius, ph, heading, phase);
			}
		}
		g.dispose();
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
			ImageIO.write(atlas, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("atlas bake failed", e);
		}
	}

	private AtlasBaker() {
	}
}
