package net.hedinger.prototype.render;

import java.awt.Graphics;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.PheromoneCloud;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.entities.Bullet;
import net.hedinger.prototype.entities.Door;
import net.hedinger.prototype.entities.Explosion;
import net.hedinger.prototype.entities.Grenade;
import net.hedinger.prototype.entities.Item;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.entities.Sound;
import net.hedinger.prototype.entities.Switch;

/**
 * The entity render layer: every visual an entity has is painted here, from
 * state read through the entity's public accessors. The domain classes hold no
 * drawing code — the sim can run without a single {@code java.awt.Graphics} in
 * an entity file, and the painters can restyle a creature without touching the
 * simulation. This layer serves the pre-rendering pipeline: the server's layer
 * bake and sprite atlases, and the scenario suite's captures and GIFs.
 *
 * <p>{@link #render} is the single entry point (called from the grid's painter
 * passes). It reproduces the old {@code Entity.render} contract exactly:
 * screen-cull first, then the corpse or the living body by sign of age.
 */
public final class EntityPainters {

	/** Draws one entity: cull, then corpse or body — the painter dispatch. */
	public static void render(Entity e, Graphics g, View v) {
		if (!visible(e, g, v)) {
			return;
		}
		if (e.getAge() < 0) {
			drawDead(e, g, v);
		} else {
			draw(e, g, v);
		}
	}

	private static void draw(Entity e, Graphics g, View v) {
		// Most-derived first: an Item is an NPC, so it must match before NPC.
		if (e instanceof Item item) {
			ItemPainter.draw(item, g, v);
		} else if (e instanceof NPC npc) {
			NpcPainter.draw(npc, g, v);
		} else if (e instanceof Door door) {
			DoorPainter.draw(door, g, v);
		} else if (e instanceof Switch sw) {
			SwitchPainter.draw(sw, g, v);
		} else if (e instanceof net.hedinger.prototype.entities.Nest nest) {
			NestPainter.draw(nest, g, v);
		} else if (e instanceof PheromoneCloud cloud) {
			PheromonePainter.draw(cloud, g, v);
		} else if (e instanceof Bullet bullet) {
			EffectPainters.drawBullet(bullet, g, v);
		} else if (e instanceof Grenade grenade) {
			EffectPainters.drawGrenade(grenade, g, v);
		} else if (e instanceof Explosion explosion) {
			EffectPainters.drawExplosion(explosion, g, v);
		} else if (e instanceof Sound sound) {
			EffectPainters.drawSound(sound, g, v);
		}
		// Weapons (and anything unlisted) have no body of their own to draw.
	}

	private static void drawDead(Entity e, Graphics g, View v) {
		// Only creatures leave a visible corpse (items included: they are NPCs).
		if (e instanceof NPC npc) {
			NpcPainter.drawDead(npc, g, v);
		}
	}

	/** The old {@code Entity.isVisible} screen cull, verbatim: on-camera-level,
	 *  inside the clip with a body-sized margin, and not hidden by fog. */
	private static boolean visible(Entity e, Graphics g, View v) {
		double zk = v.getCamZ() - e.getZ() + 1;
		int width = (int) g.getClipBounds().getMaxX();
		int height = (int) g.getClipBounds().getMaxY();
		if (zk <= 0) {
			return false;
		}
		int margin = e.getCullMargin();
		if (px(e, v, 0) > width + margin || px(e, v, 0) < -margin) {
			return false;
		}
		if (py(e, v, 0) > height + margin || py(e, v, 0) < -margin) {
			return false;
		}
		if (e.getAge() > 0 && e.getWorld().hasFog()) {
			if (e.isHostile() && !e.isDetected()) {
				return false;
			}
		}
		return true;
	}

	// ---- shared pixel plumbing (the old Entity protected helpers) ----------

	/** Screen x of the entity's position, plus a pixel offset. */
	static int px(Entity e, View v, double pixelOffset) {
		return v.pixelX(e.getX(), e.getZ(), Entity.round(pixelOffset));
	}

	/** Screen y of the entity's position, plus a pixel offset. */
	static int py(Entity e, View v, double pixelOffset) {
		return v.pixelY(e.getY(), e.getZ(), Entity.round(pixelOffset));
	}

	/** Tiles-to-screen-pixels at the entity's depth. */
	static int toPixel(Entity e, View v, double tiles) {
		return Utils.toPixel(tiles, (int) e.getZ(), v.getCamZ());
	}

	private EntityPainters() {
	}
}
