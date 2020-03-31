package net.hedinger.prototype.engine;

import static net.hedinger.prototype.engine.ResourceManager.tileSize;
import static net.hedinger.prototype.engine.View.ViewMode.BASIC;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;

public class View {

	final static Color bg = new Color(0, 0, 150);

	private ViewMode viewmode = BASIC;

	int windowX, windowY;

	private float camX, camY, camZ;
	public float mouseX, mouseY, mouseZ;

	private HashMap<Integer, Integer> overlays;
	private HashMap<Integer, Integer> underlays;

	public View() {
		overlays = new HashMap<Integer, Integer>();
		underlays = new HashMap<Integer, Integer>();
	}

	public void think(Graphics g, float cx, float cy, float cz, int mx, int my) {
		camX = cx;
		camY = cy;
		camZ = cz;
		windowX = (int) g.getClipBounds().getMaxX();
		windowY = (int) g.getClipBounds().getMaxY();

		float tilesX = mouseX;
		tilesX = tilesX / tileSize - 0.5f * windowX / tileSize;
		float tilesY = mouseY;
		tilesY = tilesY / tileSize - 0.5f * windowY / tileSize;

		mouseX = camX + tilesX;
		mouseY = camY + tilesY;
		if ((int) (camZ) == camZ) {
			mouseZ = camZ;
		} else {
			mouseZ = (int) (camZ + 1);
		}

	}

	public void render(Graphics g) {
		clearScreen(g);
	}

	public void clearScreen(Graphics g) {
		Graphics2D graphics = (Graphics2D) g;
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(bg);
		graphics.fillRect(0, 0, windowX, windowY);
	}


	public int pixelX(double x, double z, float pixelOffset) {
		return Utils.toPixelShift(pixelOffset, (float) x, (int) z, getCamX(), getCamZ(), windowX);
	}

	public int pixelY(double y, double z, float pixelOffset) {
		return Utils.toPixelShift(pixelOffset, (float) y, (int) z, getCamY(), getCamZ(), windowY);
	}

	public void cycleViewMode() {
		viewmode = viewmode.next();
	}

	public ViewMode getViewMode() {
		return viewmode;
	}

	public float getCamX() {
		return camX;
	}

	public float getCamY() {
		return camY;
	}

	public int getCamZ() {
		return (int) camZ;
	}

	public float getMouseX() {
		return mouseX;
	}

	public float getMouseY() {
		return mouseY;
	}

	public int getMouseZ() {
		return (int) mouseZ;
	}

	public enum ViewMode {
		BASIC(0),
		UNDERLAYS(1),
		OVERLAYS(2),
		ALL(3);

		private int index;
		private static Map<Integer, ViewMode> map = new HashMap<>();

		private ViewMode(int index) {
			this.index = index;
		}

		static {
			for (ViewMode enu : ViewMode.values()) {
				map.put(enu.index, enu);
			}
		}

		public ViewMode next() {
			return valueOf((index + 1) % map.size());
		}

		public static ViewMode valueOf(int index) {
			return map.get(index);
		}

		public int getIndex() {
			return index;
		}
	}
}
