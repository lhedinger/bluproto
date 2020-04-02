package net.hedinger.prototype.engine;

import static net.hedinger.prototype.engine.ResourceManager.tileSize;
import static net.hedinger.prototype.engine.View.ViewMode.BASIC;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;

import net.hedinger.prototype.entities.NPC;

public class View {

	final static Color bg = new Color(0, 0, 150);

	private ViewMode viewmode = BASIC;

	int windowX, windowY;

	private float camX, camY, camZ;
	public float mouseX, mouseY, mouseZ;

	private HashMap<Integer, Integer> overlays;
	private HashMap<Integer, Integer> underlays;

	private boolean showMinimap = true;
	public static float minimap_scale = 0.5f;
	int minimap_ping = 0;

	World world;
	LayerRenderer layerRenderer;

	public View(World world, LayerRenderer layerRenderer) {
		overlays = new HashMap<Integer, Integer>();
		underlays = new HashMap<Integer, Integer>();

		this.world = world;
		this.layerRenderer = layerRenderer;
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
		renderMinimap(g);
	}

	public void clearScreen(Graphics g) {
		Graphics2D graphics = (Graphics2D) g;
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(bg);
		graphics.fillRect(0, 0, windowX, windowY);
	}

	public void renderMinimap(Graphics g) {
		if (showMinimap) {
			Graphics2D g2 = (Graphics2D) g;

			// FIXME optimize
			double widthr = (int) g2.getClipBounds().getWidth();
			widthr = widthr / (ResourceManager.tileSize * world.cols);
			double heightr = (int) g2.getClipBounds().getHeight();
			heightr = heightr / (ResourceManager.tileSize * world.rows);
			double width = world.cols / minimap_scale;
			double height = world.rows / minimap_scale;
			widthr = widthr * width;
			heightr = heightr * height;
			g2.setColor(new Color(0, 0, 0, 150));
			g2.fillRect(25, 25, (int) Math.round(width), (int) Math.round(height));
			g2.setColor(Color.BLUE);
			g2.fillRect(20, 20, (int) Math.round(width), (int) Math.round(height));
			g2.drawImage(layerRenderer.mapLayers[(int) camZ].image_layer_thumb, 20, 20, null);
			g2.setColor(Color.WHITE);
			g2.setStroke(new BasicStroke(2));
			g2.drawRect(20 + (int) Math.round(getCamX() / minimap_scale - widthr / 2),
					20 + (int) Math.round(getCamY() / minimap_scale - heightr / 2), (int) Math.round(widthr),
					(int) Math.round(heightr));
			width = world.cols / minimap_scale;
			height = world.rows / minimap_scale;
			g2.drawRect(20, 20, (int) Math.round(width), (int) Math.round(height));

			g2.setFont(new Font("Arial", Font.BOLD, 14));
			g2.drawImage(ResourceManager.getOverlay(5), (int) g2.getClipBounds().getWidth() - 86, 6, null);
		}
	}

	public void renderMinimapMarkers(Graphics g) {
		Graphics2D g2 = (Graphics2D) g;
		for (Entity e : world.entities.values()) {
			if (e instanceof NPC) {
				NPC n = (NPC) e;
				if (n != null && !n.isDead()) {
					if (n.isHostile() && n.isDetected()) {
						g2.setColor(Color.RED);
						g2.fillOval((int) (19 + Math.round(e.getX() / minimap_scale)),
								(int) (19 + Math.round(e.getY() / minimap_scale)), 2, 2);
						g2.setStroke(new BasicStroke(1));

						g2.drawOval(
								(int) (19 - minimap_ping / 2 + Math.round(e.getX() / minimap_scale)),
								(int) (19 - minimap_ping / 2 + Math.round(e.getY() / minimap_scale)),
								minimap_ping,
								minimap_ping);
					} else {
						if (n.isFriendly()) {
							g2.setColor(Color.GREEN);
							g2.fillOval((int) (19 + Math.round(e.getX() / minimap_scale)),
									(int) (19 + Math.round(e.getY() / minimap_scale)), 2, 2);
						} else {
							g2.setColor(Color.WHITE);
							g2.fillOval((int) (19 + Math.round(e.getX() / minimap_scale)),
									(int) (19 + Math.round(e.getY() / minimap_scale)), 1, 1);
						}
					}
				}
			}
		}
	}

	public int getMiniMapWidth() {
		double width = world.cols / minimap_scale;

		return (int) Math.round(width);
	}

	public int getMiniMapHeight() {
		double height = world.rows / minimap_scale;

		return (int) Math.round(height);
	}

	public int getMiniMapX() {
		return 20;
	}

	public int getMiniMapY() {
		return 20;
	}

	public float getMiniMapScale() {
		return minimap_scale;
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
