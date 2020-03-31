package net.hedinger.prototype.engine;

import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.awt.image.WritableRaster;
import java.io.File;

import javax.imageio.ImageIO;

public class ResourceManager {

	public final static int tilemapRows = 22;
	public final static int tileSize = 64;
	public final static int wall_variations = 2;

	private static BufferedImage[] tilemap = new BufferedImage[tilemapRows * 3];
	private static BufferedImage[] propmap = new BufferedImage[11];
	private static BufferedImage[] npcs = new BufferedImage[3];
	private static BufferedImage[] npcs_dead = new BufferedImage[3];
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

		for (int i = 0; i < overlay.length; i++) {
			overlay_shadow[i] = shadowImage(overlay[i]);
		}

		try {
			loadTileMap();
			loadPropMap();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static BufferedImage getSubTile(int id, int var) {
		int i = id + var * tilemapRows;
		return tilemap[i];
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

	private static void loadTileMap() throws Exception {
		String filename = "res/tiles/tilemap" + tileSize + ".png";

		int hts = tileSize / 2;

		BufferedImage tm = null;

		tm = ImageIO.read(new File(filename));
		BufferedImage temp = null;

		for (int i = 0; i < tilemap.length; i++) {
			int y = i / tilemapRows;
			int x = i % tilemapRows;
			temp = tm.getSubimage(tileSize * x + hts, hts + tileSize * y, hts, hts);

			if (temp != null) {
				tilemap[i] = temp;
			}
		}
	}

	private static void loadPropMap() throws Exception {
		String filename = "res/tiles/tilemap" + tileSize + ".png";

		int hts = tileSize / 2;

		BufferedImage tm = null;

		tm = ImageIO.read(new File(filename));
		BufferedImage temp = null;

		System.out.println(filename + " = " + tm.getWidth() + " x " + tm.getHeight());

		int offsetY = 32 * 9;
		for (int i = 0; i < propmap.length; i++) {
			int y = i / 11;
			int x = i % 11;
			temp = tm.getSubimage(tileSize * 2 * x + hts, offsetY + tileSize * 2 * y, tileSize, tileSize);

			if (temp != null) {
				propmap[i] = temp;
			}
		}
	}

	public static BufferedImage getSubTile(String tilecode, int loc, int var) {
		int i = -1;
		if (loc == 0) // A
		{
			if (tilecode.contains("1") && tilecode.contains("2") && tilecode.contains("4")) {
				i = 0;
			} else if (tilecode.contains("9")) {
				i = 0;
			} else if (tilecode.contains("2") && tilecode.contains("4")) {
				i = 13;
			} else if (tilecode.contains("2")) {
				i = 9;
			} else if (tilecode.contains("4")) {
				i = 5;
			} else if (tilecode.contains("1")) {
				i = 17;
			} else {
				i = 1;
			}
		} else if (loc == 1) // B
		{
			if (tilecode.contains("2") && tilecode.contains("3") && tilecode.contains("5")) {
				i = 0;
			} else if (tilecode.contains("9")) {
				i = 0;
			} else if (tilecode.contains("2") && tilecode.contains("5")) {
				i = 14;
			} else if (tilecode.contains("5")) {
				i = 10;
			} else if (tilecode.contains("2")) {
				i = 6;
			} else if (tilecode.contains("3")) {
				i = 18;
			} else {
				i = 2;
			}
		} else if (loc == 2) // C
		{
			if (tilecode.contains("5") && tilecode.contains("7") && tilecode.contains("8")) {
				i = 0;
			} else if (tilecode.contains("9")) {
				i = 0;
			} else if (tilecode.contains("5") && tilecode.contains("7")) {
				i = 15;
			} else if (tilecode.contains("7")) {
				i = 11;
			} else if (tilecode.contains("5")) {
				i = 7;
			} else if (tilecode.contains("8")) {
				i = 19;
			} else {
				i = 3;
			}
		} else if (loc == 3) // D
		{
			if (tilecode.contains("4") && tilecode.contains("6") && tilecode.contains("7")) {
				i = 0;
			} else if (tilecode.contains("9")) {
				i = 0;
			} else if (tilecode.contains("4") && tilecode.contains("7")) {
				i = 16;
			} else if (tilecode.contains("4")) {
				i = 12;
			} else if (tilecode.contains("7")) {
				i = 8;
			} else if (tilecode.contains("6")) {
				i = 20;
			} else {
				i = 4;
			}
		}

		if (i == -1) {
			throw new RuntimeException();
		}

		return getSubTile(i, var);
	}

	public static BufferedImage getWallTile(String tilecode) {
		int varA = (int) (Math.random() * wall_variations);
		int varB = (int) (Math.random() * wall_variations);
		int varC = (int) (Math.random() * wall_variations);
		int varD = (int) (Math.random() * wall_variations);

		if (Math.random() * 2 < 1) {
			varA = 0;
		}
		if (Math.random() * 2 < 1) {
			varB = 0;
		}
		if (Math.random() * 2 < 1) {
			varC = 0;
		}
		if (Math.random() * 2 < 1) {
			varD = 0;
		}

		Image subA = getSubTile(tilecode, 0, varA);
		Image subB = getSubTile(tilecode, 1, varB);
		Image subC = getSubTile(tilecode, 2, varC);
		Image subD = getSubTile(tilecode, 3, varD);

		int hts = tileSize / 2;
		BufferedImage dimg = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = dimg.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(subA, 0, 0, hts, hts, null);
		g.drawImage(subB, hts, 0, hts, hts, null);
		g.drawImage(subC, hts, hts, hts, hts, null);
		g.drawImage(subD, 0, hts, hts, hts, null);
		g.dispose();
		return dimg;
	}

	public static BufferedImage getHoleTile(String tilecode) {
		Image subA = getSubTile(tilecode, 0, 2);
		Image subB = getSubTile(tilecode, 1, 2);
		Image subC = getSubTile(tilecode, 2, 2);
		Image subD = getSubTile(tilecode, 3, 2);

		int hts = tileSize / 2;
		BufferedImage dimg = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = dimg.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(subA, 0, 0, hts, hts, null);
		g.drawImage(subB, hts, 0, hts, hts, null);
		g.drawImage(subC, hts, hts, hts, hts, null);
		g.drawImage(subD, 0, hts, hts, hts, null);
		g.dispose();
		return dimg;
	}

	public static BufferedImage getRamptile(String tilecode, boolean up) {

		Image tile;
		if (up) {
			tile = getPropTile(0);
		} else {
			tile = getPropTile(1);
		}

		BufferedImage dimg = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = dimg.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(tile, 0, 0, tileSize, tileSize, null);
		g.dispose();
		return dimg;
	}

	public static BufferedImage getFloorTile(String tilecode) {
		Image subA = getSubTile(tilemapRows - 1, 0);
		Image subB = getSubTile(tilemapRows - 1, 0);
		Image subC = getSubTile(tilemapRows - 1, 0);
		Image subD = getSubTile(tilemapRows - 1, 0);

		int hts = tileSize;
		BufferedImage dimg = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = dimg.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(subA, 0, 0, hts, hts, null);
		g.drawImage(subB, hts, 0, hts, hts, null);
		g.drawImage(subC, hts, hts, hts, hts, null);
		g.drawImage(subD, 0, hts, hts, hts, null);
		g.dispose();
		return dimg;
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
		if (input == null) {
			return null;
		}
		BufferedImage output = new BufferedImage(input.getWidth(), input.getHeight(),
				BufferedImage.TYPE_4BYTE_ABGR);
		float data2[] = {
				0.0625f, 0.125f, 0.0625f,
				0.125f, 0.25f, 0.125f,
				0.0625f, 0.125f, 0.0625f };

		Kernel kernel = new Kernel(3, 3, data2);
		ConvolveOp convolve = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
		convolve.filter(input, output);
		return output;
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
