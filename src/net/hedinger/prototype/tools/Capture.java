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
 *   java -cp bin net.hedinger.prototype.tools.Capture sheet  out=sheet.png ticks=240 every=8 grid=3x3
 *   java -cp bin net.hedinger.prototype.tools.Capture mp4    out=run.mp4 ticks=120 every=2 delay=50
 *   java -cp bin net.hedinger.prototype.tools.Capture frames out=frames/ ticks=120 every=2
 * </pre>
 *
 * <p>{@code sheet} tiles sampled frames into one static PNG (each cell labeled
 * with its tick) -- use it when the viewing surface does not autoplay GIFs.
 * {@code mp4} (alias {@code h264}) encodes a universal H.264/yuv420p MP4 via
 * ffmpeg if present, falling back to a pure-Java Motion-JPEG {@code .mov}; the
 * {@code mov} mode forces the MJPEG container. {@code quality} (0..1) tunes the
 * MJPEG JPEG size.</p>
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
		float quality = floatArg(a, "quality", 0.85f);

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
		} else if (mode.equals("sheet")) {
			int[] grid = parseGrid(a.getOrDefault("grid", "3x3"));
			BufferedImage sheet = makeContactSheet(capt, grid[0], grid[1], every);
			File out = new File(a.getOrDefault("out", "sheet.png"));
			ensureParent(out);
			ImageIO.write(sheet, "png", out);
			System.out.println("wrote " + out.getAbsolutePath() + " (" + grid[0] + "x" + grid[1]
					+ " contact sheet from " + captured + " captured frames, seed " + seed + ")");
		} else if (mode.equals("mov")) {
			File out = new File(a.getOrDefault("out", "world.mov"));
			ensureParent(out);
			MjpegMp4Writer.write(out, capt, delay, quality);
			System.out.println("wrote " + out.getAbsolutePath() + " (" + captured + " frames @ "
					+ Math.round(1000.0 / delay) + "fps, MJPEG/QuickTime, seed " + seed + ")");
		} else if (mode.equals("mp4") || mode.equals("h264")) {
			int fps = Math.max(1, (int) Math.round(1000.0 / delay));
			File out = new File(a.getOrDefault("out", "world.mp4"));
			ensureParent(out);
			if (encodeH264(capt, fps, out)) {
				System.out.println("wrote " + out.getAbsolutePath() + " (" + captured + " frames @ " + fps
						+ "fps, H.264/yuv420p, seed " + seed + ")");
			} else {
				// ffmpeg unavailable: fall back to the pure-Java MJPEG container
				File mov = new File(out.getPath().replaceFirst("\\.[^.]+$", "") + ".mov");
				MjpegMp4Writer.write(mov, capt, delay, quality);
				System.out.println("ffmpeg not found -- wrote pure-Java MJPEG fallback: " + mov.getAbsolutePath());
				System.out.println("(install ffmpeg, e.g. 'apt-get install -y ffmpeg', for a universal H.264 .mp4)");
			}
		} else {
			File out = new File(a.getOrDefault("out", "world.gif"));
			ensureParent(out);
			writeGif(out, capt, delay, true);
			System.out.println("wrote " + out.getAbsolutePath() + " (" + captured + " frames, "
					+ delay + "ms each, seed " + seed + ")");
		}
	}

	// ---- H.264 via ffmpeg ----------------------------------------------

	/**
	 * Encodes frames to a broadly-compatible H.264 (yuv420p, +faststart) MP4 by
	 * piping PNGs through ffmpeg. Returns false if ffmpeg is not available, so
	 * the caller can fall back. Dimensions are forced even (yuv420p requires it).
	 */
	private static boolean encodeH264(List<BufferedImage> frames, int fps, File out) throws IOException {
		String ff = findFfmpeg();
		if (ff == null) {
			return false;
		}
		File tmp = java.nio.file.Files.createTempDirectory("blu_h264_").toFile();
		try {
			int i = 0;
			for (BufferedImage f : frames) {
				ImageIO.write(f, "png", new File(tmp, String.format("frame_%05d.png", i++)));
			}
			List<String> cmd = new ArrayList<String>();
			java.util.Collections.addAll(cmd, ff, "-y", "-loglevel", "error",
					"-framerate", Integer.toString(fps),
					"-i", new File(tmp, "frame_%05d.png").getPath(),
					"-c:v", "libx264", "-preset", "medium", "-crf", "20",
					"-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
					"-pix_fmt", "yuv420p", "-movflags", "+faststart",
					out.getPath());
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			byte[] buf = new byte[4096];
			java.io.InputStream is = p.getInputStream();
			StringBuilder log = new StringBuilder();
			int r;
			while ((r = is.read(buf)) > 0) {
				log.append(new String(buf, 0, r));
			}
			int code = p.waitFor();
			if (code != 0) {
				System.out.println("ffmpeg failed (exit " + code + "):\n" + log);
				return false;
			}
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} finally {
			for (File f : tmp.listFiles()) {
				f.delete();
			}
			tmp.delete();
		}
	}

	private static String findFfmpeg() {
		for (String c : new String[] { "ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg" }) {
			try {
				Process p = new ProcessBuilder(c, "-version").redirectErrorStream(true).start();
				p.getInputStream().readAllBytes();
				if (p.waitFor() == 0) {
					return c;
				}
			} catch (Exception e) {
				// try next candidate
			}
		}
		return null;
	}

	// ---- contact sheet --------------------------------------------------

	private static int[] parseGrid(String s) {
		int x = s.toLowerCase().indexOf('x');
		if (x > 0) {
			int c = Utils.parseInt(s.substring(0, x), 3);
			int r = Utils.parseInt(s.substring(x + 1), 3);
			return new int[] { Math.max(1, c), Math.max(1, r) };
		}
		return new int[] { 3, 3 };
	}

	/**
	 * Tiles up to cols*rows captured frames into one static PNG, evenly sampled
	 * across the run, each cell labeled with its frame index. Useful when the
	 * viewing surface does not autoplay GIFs.
	 */
	private static BufferedImage makeContactSheet(List<BufferedImage> frames, int cols, int rows, int every) {
		int cells = Math.min(frames.size(), cols * rows);
		if (cells == 0) {
			return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		}
		int cw = frames.get(0).getWidth();
		int ch = frames.get(0).getHeight();
		int gap = 6;
		int sheetW = cols * cw + (cols + 1) * gap;
		int sheetH = rows * ch + (rows + 1) * gap;
		BufferedImage sheet = new BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = sheet.createGraphics();
		g.setColor(new java.awt.Color(20, 20, 28));
		g.fillRect(0, 0, sheetW, sheetH);
		g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 14));
		for (int idx = 0; idx < cells; idx++) {
			// evenly sample across the whole capture range
			int fi = (cells == 1) ? 0 : idx * (frames.size() - 1) / (cells - 1);
			int gx = idx % cols, gy = idx / cols;
			int px = gap + gx * (cw + gap), py = gap + gy * (ch + gap);
			g.drawImage(frames.get(fi), px, py, null);
			g.setColor(new java.awt.Color(0, 0, 0, 160));
			g.fillRect(px, py, 70, 18);
			g.setColor(java.awt.Color.white);
			g.drawString("t=" + (fi * every), px + 4, py + 14);
		}
		g.dispose();
		return sheet;
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
