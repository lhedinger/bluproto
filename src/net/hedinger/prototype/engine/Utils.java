package net.hedinger.prototype.engine;

import static net.hedinger.prototype.engine.ResourceManager.tileSize;

import static java.lang.Math.round;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public class Utils {

	public static float scaleZ(int entityLevel, int camLevel) {
		float scale = tileSize;
		if (camLevel != entityLevel) {
			float zk = camLevel - entityLevel + 1;
			scale = (float) (scale / Math.pow(zk, 0.05));
		}
		return scale;
	}

	public static float scaleZ2(int entityLevel, int camLevel, int size) {
		float scale = size;
		if (camLevel != entityLevel) {
			float zk = camLevel - entityLevel + 1;
			scale = (float) (scale / Math.pow(zk, 0.05));
		}
		return scale;
	}

	public static float toTile(int pixel, int entityLevel, int camLevel) {
		float temp = pixel;
		return temp / scaleZ(entityLevel, camLevel);
	}

	public static int toPixel(double pos, int entityLevel, int camLevel) {
		return (int) round(pos * scaleZ(entityLevel, camLevel));
	}

	public static int toPixel(float pos, int entityLevel, int camLevel) {
		return round(pos * scaleZ(entityLevel, camLevel));
	}

	public static int pixelX(Graphics g, View v, double x, double z, float pixelOffset) {
		int width = (int) g.getClipBounds().getMaxX();
		return Utils.toPixelShift(pixelOffset, (float) x, (int) z, v.getCamX(), v.getCamZ(), width);
	}

	public static int pixelY(Graphics g, View v, double y, double z, float pixelOffset) {
		int width = (int) g.getClipBounds().getMaxY();
		return Utils.toPixelShift(pixelOffset, (float) y, (int) z, v.getCamY(), v.getCamZ(), width);
	}

	public static int pixelX(Graphics g, View v, float pixelOffset) {
		int width = (int) g.getClipBounds().getMaxX();
		return Utils.toPixelShift(pixelOffset, v.getMouseX(), v.getCamZ(), v.getCamX(), v.getCamZ(), width);
	}

	public static int pixelY(Graphics g, View v, float pixelOffset) {
		int width = (int) g.getClipBounds().getMaxY();
		return Utils.toPixelShift(pixelOffset, v.getMouseY(), v.getCamZ(), v.getCamY(), v.getCamZ(), width);
	}

	public static int toPixelShift(float pixelOffset, float entityXY, int entityLevel, float camXY, int camLevel,
			int windowWidth) {
		float ts = scaleZ(entityLevel, camLevel);

		return round((entityXY - camXY) * ts + windowWidth / 2f - pixelOffset);
	}

	public static BufferedImage resize(BufferedImage img, int aWidth, int aHeight) {
		if (img == null) {
			return null;
		}
		int width = img.getWidth();
		int height = img.getHeight();
		BufferedImage dimg = new BufferedImage(aWidth, aHeight, img.getType());
		Graphics2D g = dimg.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(img, 0, 0, aWidth, aHeight, 0, 0, width, height, null);
		g.dispose();
		return dimg;
	}

	public static int divCeil(int value, int divisor) {
		return (int) Math.ceil((float) value / divisor);
	}

	public static int random(int range) {
		return (int) Math.floor(Math.random() * range);
	}

	public static int parseInt(String s, int invalid) {
		int i;
		try {
			i = Integer.parseInt(s);
		} catch (Exception e) {
			i = invalid;
		}
		return i;
	}

	public static double normalizeAngle(double angle) {
		if (angle > 2 * Math.PI) {
			return angle - 2 * Math.PI;
		}
		if (angle < 0) {
			return angle + 2 * Math.PI;
		}
		return angle;
	}

}
