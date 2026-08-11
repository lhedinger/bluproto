package net.hedinger.prototype.simtest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.FileImageOutputStream;

/**
 * Records any {@link SimTests} scenario as an animated GIF: replays the
 * scenario by name (its worlds are deterministic, so the recording IS the
 * test), tapping one rendered frame per simulation tick through
 * {@link Scenario#frameSink} and encoding at ~30 fps -- which, at the sim's
 * ~33 ticks/sec, plays back in close to real time. Frames use the snapshot
 * renderer with the debug overlay on, so door edges, action labels and carry
 * links are readable in the result.
 *
 * <p>Usage: {@code RecordScenario <ScenarioName>|* [--every N]}
 *
 * <p>Output lands in {@code recordings/<ScenarioName>.gif} (git-ignored; the
 * server's {@code /recordings} endpoint serves the directory as a gallery).
 * {@code --every N} subsamples to one frame per N ticks for smaller files at
 * N-times playback speed. A failing scenario still records -- watching a
 * red test is half the point of a recording.
 */
public final class RecordScenario {

	/** Encode delay per frame in GIF centiseconds: 3 cs ~ 30 fps. */
	private static final int DELAY_CS = 3;

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("usage: RecordScenario <ScenarioName>|* [--every N]");
			return;
		}
		String want = args[0];
		int every = 1;
		for (int i = 1; i < args.length - 1; i++) {
			if (args[i].equals("--every")) {
				every = Math.max(1, Integer.parseInt(args[i + 1]));
			}
		}
		// Same render posture as the test runner, plus the debug overlay: a
		// recording is for watching, and the overlay is what narrates it.
		net.hedinger.prototype.engine.RenderFx.foliage = false;
		net.hedinger.prototype.engine.RenderFx.debugOverlay = true;
		File dir = new File("recordings");
		dir.mkdirs();

		boolean any = false;
		for (Scenario s : SimTests.all()) {
			if (!want.equals("*") && !s.name().equalsIgnoreCase(want)) {
				continue;
			}
			any = true;
			List<BufferedImage> frames = new ArrayList<BufferedImage>();
			int[] n = { 0 };
			int stride = every;
			Scenario.frameSink = w -> {
				if (n[0]++ % stride == 0) {
					frames.add(SnapshotRenderer.render(w));
				}
			};
			String verdict;
			try {
				s.run();
				verdict = "PASS";
			} catch (AssertionError e) {
				verdict = "FAIL (" + e.getMessage() + ")";
			} finally {
				Scenario.frameSink = null;
			}
			if (frames.isEmpty()) {
				System.out.println("skip  " + s.name() + ": scenario never ticked");
				continue;
			}
			File out = new File(dir, s.name() + ".gif");
			writeGif(frames, out);
			System.out.printf("%s  %s -> %s (%d frames, %.1f MB)%n", verdict, s.name(),
					out.getPath(), frames.size(), out.length() / 1e6);
		}
		if (!any) {
			System.out.println("no scenario named " + want + " (see SimTests.all)");
		}
	}

	/** Encodes frames as a looping animated GIF at {@link #DELAY_CS}/frame. */
	private static void writeGif(List<BufferedImage> frames, File out) throws Exception {
		ImageWriter wr = ImageIO.getImageWritersBySuffix("gif").next();
		FileImageOutputStream os = new FileImageOutputStream(out);
		wr.setOutput(os);
		wr.prepareWriteSequence(null);
		for (BufferedImage f : frames) {
			IIOMetadata md = wr.getDefaultImageMetadata(
					ImageTypeSpecifier.createFromRenderedImage(f), wr.getDefaultWriteParam());
			String fmt = md.getNativeMetadataFormatName();
			IIOMetadataNode root = (IIOMetadataNode) md.getAsTree(fmt);
			IIOMetadataNode gce = child(root, "GraphicControlExtension");
			gce.setAttribute("disposalMethod", "none");
			gce.setAttribute("userInputFlag", "FALSE");
			gce.setAttribute("transparentColorFlag", "FALSE");
			gce.setAttribute("delayTime", Integer.toString(DELAY_CS));
			gce.setAttribute("transparentColorIndex", "0");
			IIOMetadataNode apps = child(root, "ApplicationExtensions");
			IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
			app.setAttribute("applicationID", "NETSCAPE");
			app.setAttribute("authenticationCode", "2.0");
			app.setUserObject(new byte[] { 1, 0, 0 }); // loop forever
			apps.appendChild(app);
			md.setFromTree(fmt, root);
			wr.writeToSequence(new IIOImage(f, null, md), wr.getDefaultWriteParam());
		}
		wr.endWriteSequence();
		os.close();
	}

	private static IIOMetadataNode child(IIOMetadataNode root, String name) {
		for (int i = 0; i < root.getLength(); i++) {
			if (root.item(i).getNodeName().equals(name)) {
				return (IIOMetadataNode) root.item(i);
			}
		}
		IIOMetadataNode n = new IIOMetadataNode(name);
		root.appendChild(n);
		return n;
	}

	private RecordScenario() {
	}
}
