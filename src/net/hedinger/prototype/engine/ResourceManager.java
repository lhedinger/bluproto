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

	public final static int tilemapCols = 4;
	public final static int tilemapRows = 7;
	public final static int tilemapQuarter = tilemapCols * tilemapRows;
	public final static int tileSize = 64;
	public final static int subTileSize = 32;
	public final static int tilePadding = 16;

	public final static String wallmapFilename = "res/tiles/walls.png";
	public final static String floormapFilename = "res/tiles/floors.png";

	private static final int wallVariants = 3;

	private static BufferedImage[] wallmap = new BufferedImage[tilemapCols * tilemapRows * 4];
	private static BufferedImage[] floormap = new BufferedImage[tilemapCols * tilemapRows * 4];
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
			loadTiles(wallmap, wallmapFilename);
			loadTiles(floormap, floormapFilename);

			// loadPropMap();
			formatWallTiles();
		} catch (Exception e) {
			e.printStackTrace();
		}
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

	private static void formatWallTiles() {

		// corners filled
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < wallVariants; j++) {
				int quarterStart = 6 + j * tilemapCols * 2;
				wallmap[quarterStart + i * tilemapQuarter] = squash(
						renderWithMaskedLayers(wallmap[2], wallmap[quarterStart + i * tilemapQuarter],
								wallmap[quarterStart - 1 + i * tilemapQuarter]),
						floormap[3]);
			}
		}

		// walls filled
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < wallVariants; j++) {
				int quarterStart = 10 + j * tilemapCols * 2;
				wallmap[quarterStart + i * tilemapQuarter] = squash(
						renderWithMaskedLayers(wallmap[2], wallmap[quarterStart + i * tilemapQuarter],
								wallmap[quarterStart - 1 + i * tilemapQuarter]),
						floormap[3]);
			}
		}

		for (int i = 0; i < 4; i++) {
			floormap[5 + i * tilemapQuarter] = mask(wallmap[4 + i * tilemapQuarter], floormap[0]);
			floormap[9 + i * tilemapQuarter] = mask(wallmap[8 + i * tilemapQuarter], floormap[0]);
		}

	}

	private static void loadTiles(BufferedImage[] map, String filename) throws Exception {

		BufferedImage tm = null;

		tm = ImageIO.read(new File(filename));
		BufferedImage temp = null;

		for (int i = 0; i < tilemapQuarter; i++) {
			int y = i / tilemapCols;
			int x = i % tilemapCols;
			temp = tm.getSubimage(
					tileSize * x + subTileSize - tilePadding,
					tileSize * y + subTileSize - tilePadding,
					subTileSize + tilePadding * 2,
					subTileSize + tilePadding * 2);

			if (temp != null) {
				map[i] = temp;
				map[i + tilemapQuarter] = rotateClockwise90(temp, 1);
				map[i + tilemapQuarter * 2] = rotateClockwise90(temp, 2);
				map[i + tilemapQuarter * 3] = rotateClockwise90(temp, 3);
			}
		}
	}

	public static BufferedImage getWallSubtile(String tilecode, int loc, int offset) {
		int i = toTileIndex(tilecode, loc);

		if (i == 1) {
			return wallmap[2];
		}

		return wallmap[i + offset];
	}

	public static BufferedImage getHoleWallSubtile(String tilecode, int loc, int offset) {
		int i = toTileIndex(tilecode, loc);

		if (i == 1) {
			return wallmap[1];
		}

		return wallmap[i + offset];
	}

	public static BufferedImage getFloorSubtile(String tilecode, int loc, int offset) {
		int i = toTileIndex(tilecode, loc);

		return floormap[i + offset];
	}

	public static int toTileIndex(String tilecode, int loc) {

		/*
		 * 1 2 3 4 O 5 6 7 8
		 */

		if (loc == 0) {
			if (!tilecode.contains("2") && !tilecode.contains("4")) {
				// corner
				return 5 + tilemapQuarter * loc;
			}
			if (!tilecode.contains("2")) {
				// wall
				return 9 + tilemapQuarter * 0;
			}
			if (!tilecode.contains("4")) {
				// wall
				return 9 + tilemapQuarter * 3;
			}
		}
		if (loc == 1) {
			if (!tilecode.contains("2") && !tilecode.contains("5")) {
				// corner
				return 5 + tilemapQuarter * loc;
			}
			if (!tilecode.contains("2")) {
				// wall
				return 9 + tilemapQuarter * 0;
			}
			if (!tilecode.contains("5")) {
				// wall
				return 9 + tilemapQuarter * 1;
			}
		}
		if (loc == 2) {
			if (!tilecode.contains("5") && !tilecode.contains("7")) {
				// corner
				return 5 + tilemapQuarter * loc;
			}
			if (!tilecode.contains("5")) {
				// wall
				return 9 + tilemapQuarter * 1;
			}
			if (!tilecode.contains("7")) {
				// wall
				return 9 + tilemapQuarter * 2;
			}
		}
		if (loc == 3) {
			if (!tilecode.contains("4") && !tilecode.contains("7")) {
				// corner
				return 5 + tilemapQuarter * loc;
			}
			if (!tilecode.contains("7")) {
				// wall
				return 9 + tilemapQuarter * 2;
			}
			if (!tilecode.contains("4")) {
				// wall
				return 9 + tilemapQuarter * 3;
			}
		}

		// solid
		return 1;
	}

	public static BufferedImage getWallTile(String tilecode) {
		int var = 0;
		if (Utils.random(3) == 1) {
			var = (1 + Utils.random(wallVariants - 1)) * 2 * tilemapCols;
		}

		Image subA = getWallSubtile(tilecode, 0, 1 + var);
		Image subB = getWallSubtile(tilecode, 1, 1 + var);
		Image subC = getWallSubtile(tilecode, 2, 1 + var);
		Image subD = getWallSubtile(tilecode, 3, 1 + var);

		return combineSubtiles(subA, subB, subC, subD);
	}

	public static BufferedImage getHoleTile(String tilecode) {
		int var = 0;
		if (Utils.random(2) == 1) {
			var = (1 + Utils.random(wallVariants - 1)) * 2 * tilemapCols;
		}

		Image subA = getHoleWallSubtile(tilecode, 0, 0 + var);
		Image subB = getHoleWallSubtile(tilecode, 1, 0 + var);
		Image subC = getHoleWallSubtile(tilecode, 2, 0 + var);
		Image subD = getHoleWallSubtile(tilecode, 3, 0 + var);

		return combineSubtiles(subA, subB, subC, subD);
	}

	public static BufferedImage getHoleFloorTile(String tilecode) {
		Image subA = getFloorSubtile(tilecode, 0, 0);
		Image subB = getFloorSubtile(tilecode, 1, 0);
		Image subC = getFloorSubtile(tilecode, 2, 0);
		Image subD = getFloorSubtile(tilecode, 3, 0);

		return combineSubtiles(subA, subB, subC, subD);
	}

	public static BufferedImage getFloorTile(String tilecode) {
		int i = 0;

		Image subA = floormap[i];
		Image subB = floormap[i];
		Image subC = floormap[i];
		Image subD = floormap[i];

		return combineSubtiles(subA, subB, subC, subD);
		// return null;
	}

	public static BufferedImage combineSubtiles(Image subA, Image subB, Image subC, Image subD) {
		int hts = subTileSize + tilePadding * 2;
		BufferedImage dimg = new BufferedImage(tileSize + tilePadding * 2, tileSize + tilePadding * 2,
				BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = dimg.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(subA, 0, 0, hts, hts, null);
		g.drawImage(subB, hts - tilePadding * 2, 0, hts, hts, null);
		g.drawImage(subC, hts - tilePadding * 2, hts - tilePadding * 2, hts, hts, null);
		g.drawImage(subD, 0, hts - tilePadding * 2, hts, hts, null);
		g.dispose();
		return dimg;
	}

	public static BufferedImage getRamptile(String tilecode, boolean up) {



		if (up) {
			// currently always faces right
			Image subA = floormap[14];
			Image subB = floormap[14];
			Image subC = floormap[15 + tilemapQuarter];
			Image subD = floormap[15 + tilemapQuarter];
			return combineSubtiles(subA, subB, subC, subD);
		} else {
			// currently always faces left
			Image subA = floormap[15 + tilemapQuarter * 3];
			Image subB = floormap[15 + tilemapQuarter * 3];
			Image subC = floormap[14 + tilemapQuarter * 2];
			Image subD = floormap[14 + tilemapQuarter * 2];
			return combineSubtiles(subA, subB, subC, subD);
		}

		// BufferedImage dimg = new BufferedImage(tileSize, tileSize,
		// BufferedImage.TYPE_INT_ARGB);
		// Graphics2D g = dimg.createGraphics();
		// g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
		// RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		// g.drawImage(tile, 0, 0, tileSize, tileSize, null);
		// g.dispose();
		// return dimg;
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

	public static BufferedImage renderWithMaskedLayers(BufferedImage top, BufferedImage mask, BufferedImage bottom) {
		BufferedImage maskedTop = mask(mask, top);
		return squash(maskedTop, bottom);
	}

	public static BufferedImage squash(BufferedImage top, BufferedImage bottom) {

		if (top.getWidth() != bottom.getWidth()) {
			throw new RuntimeException();
		}
		if (top.getHeight() != bottom.getHeight()) {
			throw new RuntimeException();
		}

		BufferedImage finalImg = new BufferedImage(bottom.getWidth(), bottom.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D finalG = finalImg.createGraphics();
		finalG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		finalG.drawImage(bottom, 0, 0, bottom.getWidth(), bottom.getHeight(), null);
		finalG.drawImage(top, 0, 0, top.getWidth(), top.getHeight(), null);

		finalG.dispose();
		return finalImg;
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

	public static BufferedImage mask(BufferedImage mask, BufferedImage image) {

		BufferedImage output = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);

		final int width = image.getWidth();
		int[] imgData = new int[width];
		int[] maskData = new int[width];

		for (int y = 0; y < image.getHeight(); y++) {
			// fetch a line of data from each image
			image.getRGB(0, y, width, 1, imgData, 0, 1);
			mask.getRGB(0, y, width, 1, maskData, 0, 1);
			// apply the mask
			for (int x = 0; x < width; x++) {
				// mask away any alpha present
				int color = imgData[x];// & 0x00FFFFFF;
				// shift red into alpha bits
				int maskColor = (maskData[x] & 0x00FF0000) << 8;
				int maskColor2 = (maskColor | 0x00FFFFFF);
				imgData[x] = color & maskColor2;
			}
			// replace the data
			output.setRGB(0, y, width, 1, imgData, 0, 1);
		}

		return output;
	}

	public static BufferedImage rotateClockwise90(BufferedImage src, int times) {
		int width = src.getWidth();
		int height = src.getHeight();

		BufferedImage dest = new BufferedImage(height, width, src.getType());

		Graphics2D graphics2D = dest.createGraphics();
		graphics2D.translate((height - width) / 2, (height - width) / 2);
		graphics2D.rotate(times * Math.PI / 2, height / 2, width / 2);
		graphics2D.drawRenderedImage(src, null);

		return dest;
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
