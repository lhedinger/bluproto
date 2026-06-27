package net.hedinger.prototype.tools;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Minimal, dependency-free muxer that writes a Motion-JPEG video track into an
 * ISO-BMFF / QuickTime container ({@code .mov}). Each frame is a baseline JPEG
 * (via ImageIO) stored as one sample; the container boxes are written by hand.
 *
 * <p>Why MJPEG/QuickTime rather than H.264/MP4: encoding H.264 requires a codec
 * we cannot ship in pure Java. QuickTime natively supports the {@code 'jpeg'}
 * codec, so this file plays in Apple's native players (iOS Photos/Files/Safari,
 * macOS QuickTime) without any external tools. Browser {@code <video>} support
 * for MJPEG is inconsistent, so this is aimed at native mobile playback.
 *
 * <p>The container is a single video track, one sample per frame, constant
 * frame duration, self-contained (media data in the same file).
 */
public class MjpegMp4Writer {

	private static final int TS = 1000; // media + movie timescale (ms)

	public static void write(File out, List<BufferedImage> frames, int delayMs, float quality) throws IOException {
		if (frames.isEmpty()) {
			System.out.println("no frames to write");
			return;
		}
		int w = frames.get(0).getWidth();
		int h = frames.get(0).getHeight();
		int sampleDelta = Math.max(1, delayMs);
		long duration = (long) frames.size() * sampleDelta;

		// 1) Encode every frame to JPEG bytes.
		List<byte[]> jpegs = new ArrayList<byte[]>(frames.size());
		long mdatPayload = 0;
		for (BufferedImage f : frames) {
			byte[] jpg = encodeJpeg(f, quality);
			jpegs.add(jpg);
			mdatPayload += jpg.length;
		}

		// 2) Layout is ftyp + mdat + moov; sample offsets are absolute, so we
		// need ftyp's length before building stco.
		byte[] ftyp = box("ftyp", concat(fourcc("qt  "), u32(0x200), fourcc("qt  ")));
		long mdatStart = ftyp.length;
		long firstSampleOffset = mdatStart + 8; // after mdat size+type

		long[] offsets = new long[jpegs.size()];
		long cur = firstSampleOffset;
		for (int i = 0; i < jpegs.size(); i++) {
			offsets[i] = cur;
			cur += jpegs.get(i).length;
		}

		byte[] moov = buildMoov(w, h, sampleDelta, duration, frames.size(), jpegs, offsets);

		// 3) Stream to disk: ftyp, then mdat header + frames, then moov.
		FileOutputStream fos = new FileOutputStream(out);
		DataOutputStream os = new DataOutputStream(fos);
		try {
			os.write(ftyp);
			os.writeInt((int) (8 + mdatPayload)); // mdat is well under 4GB here
			os.writeBytes("mdat");
			for (byte[] jpg : jpegs) {
				os.write(jpg);
			}
			os.write(moov);
		} finally {
			os.close();
		}
	}

	// ---- moov / trak / stbl ---------------------------------------------

	private static byte[] buildMoov(int w, int h, int sampleDelta, long duration, int n, List<byte[]> jpegs,
			long[] offsets) throws IOException {
		byte[] mvhd = box("mvhd", concat(
				u32(0), // version+flags
				u32(0), u32(0), // creation, modification
				u32(TS), u32((int) duration),
				u32(0x00010000), // rate 1.0
				u16(0x0100), u16(0), u32(0), u32(0), // volume, reserved
				MATRIX,
				new byte[24], // pre_defined
				u32(2))); // next track id

		byte[] tkhd = box("tkhd", concat(
				u32(0x00000007), // version+flags: enabled, in movie, in preview
				u32(0), u32(0), u32(1), u32(0), u32((int) duration),
				new byte[8], // reserved
				u16(0), u16(0), u16(0), u16(0), // layer, alt group, volume, reserved
				MATRIX,
				u32(w << 16), u32(h << 16)));

		byte[] mdhd = box("mdhd", concat(
				u32(0), u32(0), u32(0), u32(TS), u32((int) duration),
				u16(0x55C4), u16(0))); // language 'und', pre_defined

		byte[] hdlr = box("hdlr", concat(
				u32(0), u32(0), fourcc("vide"), new byte[12],
				cstr("VideoHandler")));

		byte[] vmhd = box("vmhd", concat(u32(0x00000001), u16(0), u16(0), u16(0), u16(0)));

		byte[] url = box("url ", u32(0x00000001)); // self-contained
		byte[] dref = box("dref", concat(u32(0), u32(1), url));
		byte[] dinf = box("dinf", dref);

		byte[] stsd = box("stsd", concat(u32(0), u32(1), visualSampleEntry(w, h)));
		byte[] stts = box("stts", concat(u32(0), u32(1), u32(n), u32(sampleDelta)));
		byte[] stsc = box("stsc", concat(u32(0), u32(1), u32(1), u32(1), u32(1)));

		ByteArrayOutputStream sz = new ByteArrayOutputStream();
		sz.write(u32(0));
		sz.write(u32(0)); // sample_size 0 -> per-sample table
		sz.write(u32(n));
		for (byte[] j : jpegs) {
			sz.write(u32(j.length));
		}
		byte[] stsz = box("stsz", sz.toByteArray());

		ByteArrayOutputStream co = new ByteArrayOutputStream();
		co.write(u32(0));
		co.write(u32(n));
		for (long off : offsets) {
			co.write(u32((int) off));
		}
		byte[] stco = box("stco", co.toByteArray());

		byte[] stbl = box("stbl", concat(stsd, stts, stsc, stsz, stco));
		byte[] minf = box("minf", concat(vmhd, dinf, stbl));
		byte[] mdia = box("mdia", concat(mdhd, hdlr, minf));
		byte[] trak = box("trak", concat(tkhd, mdia));
		return box("moov", concat(mvhd, trak));
	}

	/** QuickTime 'jpeg' VisualSampleEntry. */
	private static byte[] visualSampleEntry(int w, int h) {
		byte[] compressor = new byte[32];
		byte[] name = "Motion JPEG".getBytes();
		compressor[0] = (byte) name.length;
		System.arraycopy(name, 0, compressor, 1, name.length);
		byte[] body = concat(
				new byte[6], u16(1), // reserved, data_reference_index
				u16(0), u16(0), new byte[12], // pre_defined, reserved, pre_defined[3]
				u16(w), u16(h),
				u32(0x00480000), u32(0x00480000), // 72 dpi h/v resolution
				u32(0), u16(1), // reserved, frame_count
				compressor,
				u16(0x0018), u16(0xFFFF)); // depth 24, pre_defined -1
		return box("jpeg", body);
	}

	// ---- helpers --------------------------------------------------------

	private static byte[] encodeJpeg(BufferedImage img, float quality) throws IOException {
		Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
		ImageWriter writer = it.next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		if (param.canWriteCompressed()) {
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(Math.max(0.1f, Math.min(1f, quality)));
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
		writer.setOutput(ios);
		writer.write(null, new IIOImage(img, null, null), param);
		ios.close();
		writer.dispose();
		return baos.toByteArray();
	}

	private static final byte[] MATRIX = concat(
			u32(0x00010000), u32(0), u32(0),
			u32(0), u32(0x00010000), u32(0),
			u32(0), u32(0), u32(0x40000000));

	private static byte[] box(String type, byte[] payload) {
		byte[] t = fourcc(type);
		int size = 8 + payload.length;
		return concat(u32(size), t, payload);
	}

	private static byte[] fourcc(String s) {
		byte[] b = new byte[4];
		for (int i = 0; i < 4; i++) {
			b[i] = (byte) s.charAt(i);
		}
		return b;
	}

	private static byte[] cstr(String s) {
		byte[] b = s.getBytes();
		byte[] r = new byte[b.length + 1];
		System.arraycopy(b, 0, r, 0, b.length);
		return r;
	}

	private static byte[] u32(int v) {
		return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
	}

	private static byte[] u16(int v) {
		return new byte[] { (byte) (v >>> 8), (byte) v };
	}

	private static byte[] concat(byte[]... parts) {
		int len = 0;
		for (byte[] p : parts) {
			len += p.length;
		}
		byte[] r = new byte[len];
		int o = 0;
		for (byte[] p : parts) {
			System.arraycopy(p, 0, r, o, p.length);
			o += p.length;
		}
		return r;
	}
}
