package net.hedinger.prototype.engine;

import static net.hedinger.prototype.engine.ResourceManager.tileSize;
import static net.hedinger.prototype.engine.View.ViewMode.BASIC;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import net.hedinger.prototype.entities.NPC;

public class Minimap {

	BufferedImage[] mapLayers;

	private View view;
	private World world;

	public static final float minimap_scale = 0.05f;
	public int minimap_ping = 0;
	public int minimapX, minimapY;
	public int minimapXOffset, minimapYOffset;
	public int minimapScreenWidth, minimapScreenHeight;

	public Minimap(World world, View view) {
		this.world = world;
		this.view = view;
	}

	public void render(Graphics g) {
		if (view.getViewMode().isAtLeast(BASIC)) {
			renderMinimap(g);
			// renderMinimapMarkers(g); // FIXME very slow!
		}
	}

	public void resize() {
		minimapScreenWidth = Math.round(view.windowX * minimap_scale);
		minimapScreenHeight = Math.round(view.windowY * minimap_scale);
	}

	public void init(LayerRenderer layerRenderer) {

		mapLayers = new BufferedImage[world.lvls];

		minimapX = Math.round(world.cols * tileSize * minimap_scale);
		minimapY = Math.round(world.rows * tileSize * minimap_scale);

		for (int i = 0; i < mapLayers.length; i++) {
			mapLayers[i] = Utils.resize(
					layerRenderer.mapLayers[i].image_layer,
					minimapX, minimapY);
		}
	}

	public boolean mouseInMiniMap(int mouseX, int mouseY) {
		if (mouseX <= view.getMinimapWidth() + view.getMiniMapX()) {
			if (mouseY <= view.getMinimapHeight() + view.getMiniMapY()) {
				if (mouseX >= view.getMiniMapX()) {
					if (mouseY >= view.getMiniMapY()) {
						view.camX = Utils.toTile(Math.round((mouseX - 20) / Minimap.minimap_scale), 0, 0);
						view.camY = Utils.toTile(Math.round((mouseY - 20) / Minimap.minimap_scale), 0, 0);
						return true;
					}
				}
			}
		}
		return false;
	}

	private void renderMinimap(Graphics g) {

		int offsetX = 20;
		int offsetY = 20;

		Graphics2D g2 = (Graphics2D) g;

		g2.setColor(new Color(0, 0, 0, 150));
		g2.fillRect(offsetX + 5, offsetY + 5, minimapX, minimapY);
		g2.setColor(Color.BLUE);
		g2.fillRect(offsetX, offsetY, minimapX, minimapY);
		g2.drawImage(mapLayers[view.getCamZ()], 20, 20, null);
		g2.setColor(Color.WHITE);
		g2.setStroke(new BasicStroke(2));
		g2.drawRect(
				offsetX + Math.round((view.getCamTlX()) * minimap_scale),
				offsetY + Math.round(view.getCamTlY() * minimap_scale),
				Math.round(minimapScreenWidth),
				Math.round(minimapScreenHeight));
		g2.drawRect(20, 20, minimapX, minimapY);

		g2.setFont(new Font("Arial", Font.BOLD, 14));
		g2.drawImage(ResourceManager.getOverlay(5), (int) g2.getClipBounds().getWidth() - 86, 6, null);
	}

	private void renderMinimapMarkers(Graphics g) {
		// FIXME optimize
		Graphics2D g2 = (Graphics2D) g;
		for (Entity e : world.entities.values()) {
			if (e instanceof NPC) {
				NPC n = (NPC) e;
				if (n != null && !n.isDead()) {
					if (n.isHostile() && n.isDetected()) {
						g2.setColor(Color.RED);
						g2.fillOval((int) (19 + Math.round(e.getX() / minimap_scale)),
								(int) (19 + Math.round(e.getY() / minimap_scale)), 2, 2);
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

}
