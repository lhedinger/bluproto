package net.hedinger.prototype.engine;

import static net.hedinger.prototype.engine.ResourceManager.tileSize;
import static net.hedinger.prototype.engine.View.ViewMode.BASIC;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;

public class View {

	final static Font font = new Font("Arial", 1, 12);
	final static Color bg = new Color(0, 0, 0);

	private ViewMode viewmode = BASIC;

	int windowX, windowY;

	float camX, camY, camZ;
	private int mouseX, mouseY;
	public float mouseCol, mouseRow, mouseZ;

	int chunkX, chunkY;
	final int chunkSize = 500;

	private HashMap<Integer, Integer> overlays;
	private HashMap<Integer, Integer> underlays;

	World world;
	LayerRenderer layerRenderer;

	Minimap minimap;

	public View(World world, LayerRenderer layerRenderer) {
		overlays = new HashMap<Integer, Integer>();
		underlays = new HashMap<Integer, Integer>();

		this.world = world;
		this.layerRenderer = layerRenderer;

		camX = world.cols * 0.5f;
		camY = world.rows * 0.5f;
		camZ = 0;

		minimap = new Minimap(world, this);
		minimap.init(layerRenderer);
	}

	public void resize() {
		chunkX = Math.floorDiv(windowX, chunkSize) + 1;
		chunkY = Math.floorDiv(windowY, chunkSize) + 1;

		minimap.resize();
	}

	public void think(Graphics g, float cx, float cy, float cz, int mx, int my) {
		camX += cx;
		camY += cy;
		camZ += cz;

		mouseX = mx;
		mouseY = my;

		if (camZ < 0) {
			camZ = 0;
		}
		if (camZ >= world.getLevels() - 1) {
			camZ = world.getLevels() - 1;
		}

		if (windowX != (int) g.getClipBounds().getMaxX() || windowY != (int) g.getClipBounds().getMaxY()) {
			windowX = (int) g.getClipBounds().getMaxX();
			windowY = (int) g.getClipBounds().getMaxY();
			resize();
		}

		float tilesX = mouseCol;
		tilesX = tilesX / tileSize - 0.5f * windowX / tileSize;
		float tilesY = mouseRow;
		tilesY = tilesY / tileSize - 0.5f * windowY / tileSize;

		mouseCol = camX + tilesX;
		mouseRow = camY + tilesY;
		if ((int) (camZ) == camZ) {
			mouseZ = camZ;
		} else {
			mouseZ = (int) (camZ + 1);
		}

	}

	public void render(Graphics g) {
		clearScreen(g);
		renderWorld(g);
		renderEffects(g);
		minimap.render(g);
	}

	public void mousePressed() {
		minimap.mouseInMiniMap(mouseX, mouseY);
	}

	public void clearScreen(Graphics g) {
		Graphics2D graphics = (Graphics2D) g;
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(bg);
		graphics.fillRect(0, 0, windowX, windowY);
	}

	public void renderEffects(Graphics g) {
		Graphics2D g2 = (Graphics2D) g;
		for (int x = 0; x < chunkX; x++) {
			for (int y = 0; y < chunkX; y++) {
				g2.drawImage(ResourceManager.getEffects(0), chunkSize * x, chunkSize * y, null);
			}
		}
	}

	public void renderFPS(Graphics g, int framerate) {
		Graphics2D g2 = (Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(bg);
		g2.setColor(Color.white);
		g2.setFont(font);
		g2.drawString("FPS: " + framerate, 20, 15);
		// g2.drawString(">> args =" + arglist, 250, 15);
	}

	public void renderWorld(Graphics g) {
		world.render(g, this, layerRenderer);
	}

	public int getMiniMapX() {
		return 20;
	}

	public int getMiniMapY() {
		return 20;
	}

	public int getMinimapWidth() {
		return minimap.minimapX;
	}

	public int getMinimapHeight() {
		return minimap.minimapY;
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

	public float getCamTlX() {
		return camX * tileSize - Math.round(windowX * 0.5f);
	}

	public float getCamTlY() {
		return camY * tileSize - Math.round(windowY * 0.5f);
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
		NOHUD(0),
		BASIC(1), // minimap
		UNDERLAYS(2),
		OVERLAYS(3),
		ALL(4);

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

		public boolean isAtLeast(ViewMode mode) {
			return this.index >= mode.getIndex();
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
