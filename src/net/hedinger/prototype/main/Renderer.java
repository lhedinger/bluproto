package net.hedinger.prototype.main;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.World;

/**
 * Off-screen frame rendering shared by the capture tools. Drives the real
 * {@link View} pipeline into a {@link BufferedImage} — no window, no display
 * server — so the same pixels the GUI would show can be saved to disk.
 */
public final class Renderer {

	public final int width;
	public final int height;
	private final BufferedImage canvas;

	public Renderer(int width, int height) {
		this.width = width;
		this.height = height;
		this.canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
	}

	/**
	 * Paints the current world state and returns the shared canvas. The image
	 * is reused between calls — write/encode it before the next paint.
	 */
	public BufferedImage paint(World world, View view) {
		Graphics2D g = canvas.createGraphics();
		g.setClip(0, 0, width, height); // View reads getClipBounds() for the viewport
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		view.think(g, 0, 0, 0, 0, 0);
		view.render(g);

		g.dispose();
		return canvas;
	}
}
