package net.hedinger.prototype.engine;

import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.awt.image.WritableRaster;
import java.io.File;

import javax.imageio.ImageIO;

public class ResourceManager {

	public final static int tileSize = 64;
	public final static int tilePadding = 16;

	public final static String scanline = "res/overlays/scanline_500.png";

	private static BufferedImage[] propmap = new BufferedImage[11];
	private static BufferedImage[] npcs = new BufferedImage[3];
	private static BufferedImage[] npcs_dead = new BufferedImage[3];
	private static BufferedImage[] effects = new BufferedImage[10];
	private static BufferedImage[] overlay = new BufferedImage[10];
	private static BufferedImage[] overlay_shadow = new BufferedImage[10];

	public static void loadResources() {
		npcs[0] = loadImage("res/npcs/64/dot_friendly.png");
		npcs[1] = loadImage("res/npcs/64/dot_neutral.png");
		npcs[2] = loadImage("res/npcs/64/dot_hostile.png");

		npcs_dead[0] = fadeImage(npcs[0], 0.25f);
		npcs_dead[1] = fadeImage(npcs[1], 0.25f);
		npcs_dead[2] = fadeImage(npcs[2], 0.25f);

		overlay[0] = loadImage("res/overlays/icon_offline.png");
		overlay[1] = loadImage("res/overlays/icon_probe.png");
		overlay[2] = loadImage("res/overlays/icon_drone.png");
		overlay[3] = loadImage("res/overlays/icon_sentry.png");
		overlay[4] = loadImage("res/overlays/icon_biohazard.png");
		overlay[5] = loadImage("res/overlays/icon_peep.png");

		effects[0] = loadImage(scanline);

		for (int i = 0; i < overlay.length; i++) {
			overlay_shadow[i] = shadowImage(overlay[i]);
		}

		// Walls, holes, ramps and floors are all procedurally generated now
		// (see ProcTiles), so no tile sprite sheets are loaded.
	}

	public static BufferedImage getPropTile(int id) {
		return propmap[id];
	}

	public static BufferedImage loadImage(String filename) {
		BufferedImage img = null;
		try {
			img = ImageIO.read(new File(filename));
		} catch (Exception e) {
			System.out.println("failed to load " + filename);
		}
		return img;
	}

	public static BufferedImage getWallTile(String tilecode, int variant) {
		return ProcTiles.wall(tilecode, variant);
	}

	public static BufferedImage getHoleTile(String tilecode, int variant) {
		return ProcTiles.hole(tilecode, variant);
	}

	public static BufferedImage getHoleFloorTile(String tilecode, int variant) {
		return ProcTiles.holeFloor(tilecode, variant);
	}

	public static BufferedImage getFloorTile(String tilecode) {
		return ProcTiles.floor();
	}

	public static BufferedImage getRamptile(String tilecode, boolean up) {
		return ProcTiles.ramp(tilecode, up);
	}

	public static BufferedImage getEffects(int index) {
		return effects[index];
	}

	public static BufferedImage getOverlay(int index) {
		if (index < 0 || index >= overlay.length) {
			return null;
		}

		return overlay[index];
	}

	public static BufferedImage getOverlayShadow(int index) {
		if (index < 0 || index >= overlay_shadow.length) {
			return null;
		}

		return overlay_shadow[index];
	}

	public static BufferedImage getNpcSprite(int index) {
		if (index < 0 || index >= npcs.length) {
			return null;
		}

		return npcs[index];
	}

	public static BufferedImage getCropseSprite(int index) {
		if (index < 0 || index >= npcs_dead.length) {
			return null;
		}

		return npcs_dead[index];
	}

	public static BufferedImage shadowImage(BufferedImage input) {
		if (input == null) {
			return null;
		}
		BufferedImage temp = new BufferedImage(input.getWidth() + 64, input.getHeight() + 64,
				BufferedImage.TYPE_4BYTE_ABGR);

		Graphics g = temp.createGraphics();
		g.drawImage(input, 32, 32, null);
		g.dispose();

		WritableRaster imgRaster = temp.getRaster();

		int[] pixel = new int[4];
		int i, j;

		for (i = 0; i < temp.getWidth(); i++) {
			for (j = 0; j < temp.getHeight(); j++) {
				imgRaster.getPixel(i, j, pixel);
				pixel[0] = 0;
				pixel[1] = 0;
				pixel[2] = 0;
				imgRaster.setPixel(i, j, pixel);
			}
		}

		return fadeImage(blurImage(blurImage(blurImage(temp))), 0.5f);
	}

	public static BufferedImage blurImage(BufferedImage input) {
		return blurImage(input, 0.0625f);
	}

	public static BufferedImage blurImage(BufferedImage input, int iteration) {
		BufferedImage result = input;
		for (int i = 0; i < iteration; i++) {
			result = blurImage(result, 0.0625f);
		}

		return result;
	}

	public static BufferedImage blurImage(BufferedImage input, float scale) {
		if (input == null) {
			return null;
		}
		new BufferedImage(input.getWidth(), input.getHeight(),
				BufferedImage.TYPE_4BYTE_ABGR);

		int radius = 5;
		int size = radius * 2 + 1;
		float weight = 1.0f / (size * size);
		float[] data = new float[size * size];

		for (int i = 0; i < data.length; i++) {
			data[i] = weight;
		}
		Kernel kernel = new Kernel(size, size, data);
		ConvolveOp convolve = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
		return convolve.filter(input, null);
	}

	public static BufferedImage fadeImage(BufferedImage input, float transparency) {
		if (input == null) {
			return null;
		}

		BufferedImage output = new BufferedImage(input.getWidth(), input.getHeight(),
				BufferedImage.TYPE_4BYTE_ABGR);
		Graphics2D g = output.createGraphics();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, transparency));
		g.drawImage(input, null, 0, 0);
		g.dispose();
		return output;
	}

}
