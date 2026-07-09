package net.hedinger.prototype.main;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageOutputStream;

import net.hedinger.prototype.engine.LayerRenderer;
import net.hedinger.prototype.engine.ResourceManager;
import net.hedinger.prototype.engine.StopWatch;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.World;

/**
 * Renders the running simulation off-screen — no window, no display server —
 * by driving the real {@link View} render pipeline into a {@link BufferedImage}
 * and writing PNG frames plus an animated GIF with the built-in ImageIO. This
 * is how you get a screenshot or a short "video" of the sim in a headless
 * environment.
 *
 * Usage:
 * java net.hedinger.prototype.main.SimCapture \
 *      [outDir] [frames] [ticksPerFrame] [warmup] [cols] [rows] [lvls]
 *
 * frames=1 produces a single screenshot (screenshot.png). frames&gt;1 also
 * writes sim.gif animating the sim.
 */
public class SimCapture {

	private static final int W = 900;
	private static final int H = 700;

	public static void main(String[] args) throws IOException {
		String outDir = args.length > 0 ? args[0] : "capture";
		int frames = args.length > 1 ? Integer.parseInt(args[1]) : 60;
		int ticksPerFrame = args.length > 2 ? Integer.parseInt(args[2]) : 6;
		// default warmup lands on midday (World.DAY_LENGTH multiple + noon
		// offset) so plants have grown in and the scene is well lit
		int warmup = args.length > 3 ? Integer.parseInt(args[3]) : 3000;
		int cols = args.length > 4 ? Integer.parseInt(args[4]) : 24;
		int rows = args.length > 5 ? Integer.parseInt(args[5]) : 24;
		int lvls = args.length > 6 ? Integer.parseInt(args[6]) : 2;

		File dir = new File(outDir);
		dir.mkdirs();

		ResourceManager.loadResources();
		PrototypeWorld.stopwatch = new StopWatch();

		World world = Ecosystem.build(cols, rows, lvls);
		LayerRenderer layerRenderer = new LayerRenderer(world);
		layerRenderer.build(world);
		View view = new View(world, layerRenderer);

		// let plants grow in and animals disperse before we start filming
		System.out.println("warming up " + warmup + " ticks...");
		for (int t = 0; t < warmup; t++) {
			world.think();
		}

		BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);

		if (frames <= 1) {
			renderFrame(canvas, world, view);
			File png = new File(dir, "screenshot.png");
			ImageIO.write(canvas, "png", png);
			System.out.println("wrote " + png.getPath());
			return;
		}

		// numbered PNG frames for an external encoder (e.g. ffmpeg -> mp4)
		File frameDir = new File(dir, "frames");
		frameDir.mkdirs();

		File gif = new File(dir, "sim.gif");
		GifWriter writer = new GifWriter(gif, 60, true); // ~60ms/frame
		System.out.println("capturing " + frames + " frames...");
		for (int f = 0; f < frames; f++) {
			for (int t = 0; t < ticksPerFrame; t++) {
				world.think();
			}
			renderFrame(canvas, world, view);
			writer.append(canvas);
			ImageIO.write(canvas, "png", new File(frameDir, String.format("frame_%04d.png", f)));

			if (f == 0) {
				ImageIO.write(canvas, "png", new File(dir, "screenshot.png"));
			}
		}
		writer.close();
		System.out.println("wrote " + gif.getPath() + ", " + frames + " PNG frames in "
				+ frameDir.getPath() + ", and screenshot.png");
	}

	/** Paints one frame using the same calls PrototypeWorld.paint() makes. */
	private static void renderFrame(BufferedImage canvas, World world, View view) {
		Graphics2D g = canvas.createGraphics();
		g.setClip(0, 0, W, H); // View reads getClipBounds() for the viewport
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		view.think(g, 0, 0, 0, 0, 0);
		view.render(g);

		g.dispose();
	}

	/** Minimal animated-GIF encoder built on the JDK's ImageIO GIF writer. */
	private static final class GifWriter {
		private final ImageWriter writer;
		private final ImageWriteParam params;
		private final IIOMetadata metadata;
		private final ImageOutputStream out;

		GifWriter(File file, int delayMs, boolean loop) throws IOException {
			Iterator<ImageWriter> it = ImageIO.getImageWritersBySuffix("gif");
			if (!it.hasNext()) {
				throw new IOException("no GIF image writer available");
			}
			writer = it.next();
			params = writer.getDefaultWriteParam();
			metadata = writer.getDefaultImageMetadata(
					javax.imageio.ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB),
					params);
			configure(delayMs, loop);
			out = new FileImageOutputStream(file);
			writer.setOutput(out);
			writer.prepareWriteSequence(null);
		}

		private void configure(int delayMs, boolean loop) throws IOException {
			String format = metadata.getNativeMetadataFormatName();
			IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

			node(root, "GraphicControlExtension").setAttribute("delayTime",
					Integer.toString(Math.round(delayMs / 10f)));
			node(root, "GraphicControlExtension").setAttribute("disposalMethod", "none");
			node(root, "GraphicControlExtension").setAttribute("userInputFlag", "FALSE");

			if (loop) {
				IIOMetadataNode appExts = node(root, "ApplicationExtensions");
				IIOMetadataNode appNode = new IIOMetadataNode("ApplicationExtension");
				appNode.setAttribute("applicationID", "NETSCAPE");
				appNode.setAttribute("authenticationCode", "2.0");
				appNode.setUserObject(new byte[] { 0x1, 0, 0 }); // loop forever
				appExts.appendChild(appNode);
			}
			metadata.setFromTree(format, root);
		}

		private static IIOMetadataNode node(IIOMetadataNode root, String name) {
			for (int i = 0; i < root.getLength(); i++) {
				if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
					return (IIOMetadataNode) root.item(i);
				}
			}
			IIOMetadataNode created = new IIOMetadataNode(name);
			root.appendChild(created);
			return created;
		}

		void append(BufferedImage img) throws IOException {
			writer.writeToSequence(new IIOImage(img, null, metadata), params);
		}

		void close() throws IOException {
			writer.endWriteSequence();
			out.close();
			writer.dispose();
		}
	}
}
