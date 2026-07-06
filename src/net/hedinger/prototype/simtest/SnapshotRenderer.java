package net.hedinger.prototype.simtest;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

import net.hedinger.prototype.engine.LayerRenderer;
import net.hedinger.prototype.engine.ResourceManager;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.World;

/**
 * Renders a scenario's {@link World} to an image and composes labelled
 * before/after strips. Used only when snapshot capture is enabled (see
 * {@link Scenario}); it is never on the hot path of a normal test run.
 */
final class SnapshotRenderer {

	private static boolean resourcesLoaded = false;

	private SnapshotRenderer() {
	}

	/**
	 * Renders the whole of the given level, crisply upscaled so even a tiny
	 * scenario world is readable. The render scale is fixed at tileSize px/tile,
	 * so the image is sized to the world and then integer-scaled up.
	 */
	static BufferedImage render(World w, int level) {
		if (!resourcesLoaded) {
			ResourceManager.loadResources();
			resourcesLoaded = true;
		}
		LayerRenderer lr = new LayerRenderer(w);
		lr.build(w);
		View view = new View(w, lr);

		int ts = ResourceManager.tileSize;
		int worldW = Math.max(ts, w.getColums() * ts);
		int worldH = Math.max(ts, w.getRows() * ts);

		BufferedImage raw = new BufferedImage(worldW, worldH, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = raw.createGraphics();
		g.setClip(0, 0, worldW, worldH);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// View defaults its camera to the world centre; steer to the level.
		view.think(g, 0, 0, level - view.getCamZ(), 0, 0);
		view.render(g);
		g.dispose();

		int scale = Math.max(1, (int) Math.ceil(480.0 / Math.max(worldW, worldH)));
		return upscale(raw, scale);
	}

	private static BufferedImage upscale(BufferedImage src, int scale) {
		if (scale <= 1) {
			return src;
		}
		int w = src.getWidth() * scale;
		int h = src.getHeight() * scale;
		BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = dst.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return dst;
	}

	/**
	 * Composes labelled frames left-to-right into one image with a title bar,
	 * e.g. a before/after pair for a scenario.
	 */
	static BufferedImage strip(String title, List<String> labels, List<BufferedImage> frames) {
		int pad = 12;
		int labelH = 20;
		int titleH = 26;
		int cellH = 0;
		int totalW = pad;
		for (BufferedImage f : frames) {
			cellH = Math.max(cellH, f.getHeight());
			totalW += f.getWidth() + pad;
		}
		int totalH = titleH + labelH + cellH + pad;

		BufferedImage out = new BufferedImage(Math.max(totalW, 200), totalH, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(24, 24, 30));
		g.fillRect(0, 0, out.getWidth(), out.getHeight());

		g.setColor(Color.white);
		g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 14f));
		g.drawString(title, pad, titleH - 8);

		int x = pad;
		for (int i = 0; i < frames.size(); i++) {
			BufferedImage f = frames.get(i);
			int y = titleH + labelH;
			g.setColor(new Color(200, 200, 210));
			g.setFont(g.getFont().deriveFont(java.awt.Font.PLAIN, 12f));
			g.drawString(labels.get(i), x, titleH + 14);
			g.drawImage(f, x, y, null);
			x += f.getWidth() + pad;
		}
		g.dispose();
		return out;
	}
}
