package net.hedinger.prototype.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import net.hedinger.prototype.engine.Utils;

/**
 * Command-line diagnostics capture tool. Renders a seeded world headlessly and
 * writes a screenshot, an animated GIF, or a PNG frame sequence.
 *
 * <p>Run from the repository root (resources load from {@code res/}):
 *
 * <pre>
 *   javac -d bin $(find src -name '*.java')
 *   java -cp bin net.hedinger.prototype.tools.Capture shot   out=world.png
 *   java -cp bin net.hedinger.prototype.tools.Capture gif    out=run.gif ticks=120 every=2
 *   java -cp bin net.hedinger.prototype.tools.Capture frames out=frames/ ticks=120 every=2
 * </pre>
 *
 * <p>All parameters are {@code key=value}; any subset may be supplied.
 * <ul>
 *   <li>{@code seed}     RNG seed (default 1234) -- reproduces an exact run</li>
 *   <li>{@code cols,rows,lvls}  world size (default 40,40,3)</li>
 *   <li>{@code pop}      entities to spawn (default 300)</li>
 *   <li>{@code level}    camera level / Z to render (default 0)</li>
 *   <li>{@code w,h}      output size in px (default 1280x800); ignored if whole=1</li>
 *   <li>{@code whole}    1 = size the image to the entire level (default 0)</li>
 *   <li>{@code camx,camy}  camera center in tiles (default = map center)</li>
 *   <li>{@code warmup}   ticks to run before the first captured frame (default 30)</li>
 *   <li>{@code ticks}    total ticks to simulate while capturing (gif/frames; default 120)</li>
 *   <li>{@code every}    capture one frame every N ticks (gif/frames; default 2)</li>
 *   <li>{@code delay}    GIF frame delay in ms (default 60)</li>
 *   <li>{@code scale}    output downscale divisor, e.g. 2 = half size (default 1)</li>
 *   <li>{@code out}      output path (default depends on mode)</li>
 * </ul>
 */
public class Capture {

	public static void main(String[] args) throws IOException {
		if (args.length == 0) {
			System.out.println("usage: Capture <shot|gif|frames> [key=value ...]  (run from repo root)");
			return;
		}
		String mode = args[0];
		Map<String, String> a = parseArgs(args);

		long seed = longArg(a, "seed", 1234L);
		int cols = intArg(a, "cols", 40);
		int rows = intArg(a, "rows", 40);
		int lvls = intArg(a, "lvls", 3);
		int pop = intArg(a, "pop", 300);
		int level = intArg(a, "level", 0);
		int w = intArg(a, "w", 1280);
		int h = intArg(a, "h", 800);
		boolean whole = intArg(a, "whole", 0) != 0;
		int warmup = intArg(a, "warmup", 30);
		int ticks = intArg(a, "ticks", 120);
		int every = Math.max(1, intArg(a, "every", 2));
		int delay = intArg(a, "delay", 60);
		int scale = Math.max(1, intArg(a, "scale", 1));

		System.out.println("Building world: seed=" + seed + " size=" + cols + "x" + rows + "x" + lvls
				+ " pop=" + pop + " level=" + level);
		RenderHarness h0 = new RenderHarness(seed, cols, rows, lvls, pop);
		float camx = floatArg(a, "camx", cols * 0.5f);
		float camy = floatArg(a, "camy", rows * 0.5f);

		h0.tick(warmup);

		if (mode.equals("shot")) {
			BufferedImage img = whole ? h0.wholeLevel(level) : h0.frame(w, h, camx, camy, level);
			img = downscale(img, scale);
			File out = new File(a.getOrDefault("out", "world.png"));
			ensureParent(out);
			ImageIO.write(img, "png", out);
			System.out.println("wrote " + out.getAbsolutePath() + " (" + img.getWidth() + "x" + img.getHeight()
					+ ", seed " + seed + ")");
			return;
		}

		// gif / frames: capture a frame every `every` ticks across `ticks`.
		List<BufferedImage> capt = new ArrayList<BufferedImage>();
		File framesDir = mode.equals("frames") ? new File(a.getOrDefault("out", "frames/")) : null;
		if (framesDir != null) {
			framesDir.mkdirs();
		}
		int captured = 0;
		for (int t = 0; t <= ticks; t++) {
			if (t % every == 0) {
				BufferedImage img = whole ? h0.wholeLevel(level) : h0.frame(w, h, camx, camy, level);
				img = downscale(img, scale);
				if (mode.equals("frames")) {
					File f = new File(framesDir, String.format("frame_%05d.png", captured));
					ImageIO.write(img, "png", f);
				} else {
					capt.add(img);
				}
				captured++;
			}
			if (t < ticks) {
				h0.tick(1);
			}
		}

		if (mode.equals("frames")) {
			System.out.println("wrote " + captured + " frames to " + framesDir.getAbsolutePath());
			System.out.println("encode with ffmpeg (if available):");
			System.out.println("  ffmpeg -framerate " + Math.round(1000.0 / (delay)) + " -i "
					+ framesDir.getPath() + "/frame_%05d.png -pix_fmt yuv420p out.mp4");
		} else {
			File out = new File(a.getOrDefault("out", "world.gif"));
			ensureParent(out);
			writeGif(out, capt, delay, true);
			System.out.println("wrote " + out.getAbsolutePath() + " (" + captured + " frames, "
					+ delay + "ms each, seed " + seed + ")");
		}
	}

	// ---- image helpers --------------------------------------------------

	private static BufferedImage downscale(BufferedImage img, int scale) {
		if (scale <= 1) {
			return img;
		}
		return Utils.resize(img, img.getWidth() / scale, img.getHeight() / scale);
	}

	private static void ensureParent(File f) {
		File p = f.getParentFile();
		if (p != null) {
			p.mkdirs();
		}
	}

	/** Writes an animated GIF using ImageIO with per-frame delay and looping. */
	private static void writeGif(File out, List<BufferedImage> frames, int delayMs, boolean loop) throws IOException {
		if (frames.isEmpty()) {
			System.out.println("no frames to write");
			return;
		}
		ImageWriter writer = ImageIO.getImageWritersBySuffix("gif").next();
		ImageOutputStream ios = ImageIO.createImageOutputStream(out);
		writer.setOutput(ios);
		writer.prepareWriteSequence(null);

		ImageWriteParam param = writer.getDefaultWriteParam();
		ImageTypeSpecifier type = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB);

		boolean first = true;
		for (BufferedImage frame : frames) {
			IIOMetadata meta = writer.getDefaultImageMetadata(type, param);
			configureGifMetadata(meta, delayMs, loop && first);
			writer.writeToSequence(new IIOImage(frame, null, meta), param);
			first = false;
		}
		writer.endWriteSequence();
		ios.close();
		writer.dispose();
	}

	private static void configureGifMetadata(IIOMetadata meta, int delayMs, boolean addLoop) throws IOException {
		String fmt = meta.getNativeMetadataFormatName();
		IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);

		IIOMetadataNode gce = child(root, "GraphicControlExtension");
		gce.setAttribute("disposalMethod", "none");
		gce.setAttribute("userInputFlag", "FALSE");
		gce.setAttribute("transparentColorFlag", "FALSE");
		gce.setAttribute("delayTime", Integer.toString(Math.max(1, delayMs / 10))); // hundredths of a sec
		gce.setAttribute("transparentColorIndex", "0");

		if (addLoop) {
			IIOMetadataNode appExts = child(root, "ApplicationExtensions");
			IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
			app.setAttribute("applicationID", "NETSCAPE");
			app.setAttribute("authenticationCode", "2.0");
			app.setUserObject(new byte[] { 0x1, 0x0, 0x0 }); // loop forever
			appExts.appendChild(app);
		}

		meta.setFromTree(fmt, root);
	}

	private static IIOMetadataNode child(IIOMetadataNode root, String name) {
		for (int i = 0; i < root.getLength(); i++) {
			if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
				return (IIOMetadataNode) root.item(i);
			}
		}
		IIOMetadataNode node = new IIOMetadataNode(name);
		root.appendChild(node);
		return node;
	}

	// ---- arg parsing ----------------------------------------------------

	private static Map<String, String> parseArgs(String[] args) {
		Map<String, String> m = new HashMap<String, String>();
		for (int i = 1; i < args.length; i++) {
			int eq = args[i].indexOf('=');
			if (eq > 0) {
				m.put(args[i].substring(0, eq), args[i].substring(eq + 1));
			}
		}
		return m;
	}

	private static int intArg(Map<String, String> a, String k, int d) {
		return a.containsKey(k) ? Utils.parseInt(a.get(k), d) : d;
	}

	private static float floatArg(Map<String, String> a, String k, float d) {
		try {
			return a.containsKey(k) ? Float.parseFloat(a.get(k)) : d;
		} catch (NumberFormatException e) {
			return d;
		}
	}

	private static long longArg(Map<String, String> a, String k, long d) {
		try {
			return a.containsKey(k) ? Long.parseLong(a.get(k)) : d;
		} catch (NumberFormatException e) {
			return d;
		}
	}
}
