package net.hedinger.prototype.entities;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.View;

/**
 * An inanimate object in the world: a crate, a piece of food, a hazard. Items
 * are a lightweight, brainless kind of {@link NPC} -- they occupy space, are
 * <em>perceived</em> by creatures (a dedicated item sense, see
 * {@link AgentIO#S_ITEM_PROX}), collide and get shoved aside like any other
 * body, and can be grabbed and carried. What they cannot do is <b>think</b>:
 * {@link #think()} is a no-op, so an item never perceives-to-decide or acts on
 * its own. It just sits there being interacted with.
 *
 * <p>Every item carries a {@link Kind} and a few attributes; the effect of
 * eating or attacking one depends on them:
 * <ul>
 *   <li>{@link Kind#FOOD} -- edible; eating it yields {@link #foodEnergy}
 *       energy and consumes it.</li>
 *   <li>{@link Kind#CRATE} -- not edible; it has durability, and attacking it
 *       whittles that down until it breaks and <b>spills</b> food.</li>
 *   <li>{@link Kind#HAZARD} -- eating or attacking it <b>bites back</b>,
 *       damaging the interactor (a spiky/poisonous object).</li>
 * </ul>
 *
 * <p>Items draw no RNG (built through the direction-taking {@link Entity}
 * constructor), so scattering them through a simulation never perturbs the
 * deterministic actor stream.
 */
public class Item extends NPC {

	public enum Kind {
		FOOD, CRATE, HAZARD
	}

	private final Kind kind;
	/** Energy a creature gains from eating this (FOOD). */
	private double foodEnergy = 1.0;
	/** Food items a broken crate scatters (CRATE). */
	private int spill = 3;
	/** Damage a hazard inflicts on whoever eats or attacks it (HAZARD). */
	private int hazardDamage = 20;
	/** Set once a crate has shattered, so it spills its food only a single time. */
	private boolean broken = false;

	private Item(double x, double y, double z, Kind kind) {
		super(x, y, z, 0.0); // direction-taking ctor: no RNG draw
		this.kind = kind;
		hostile = 1; // neutral: not prey, not a threat
		// Wide, omnidirectional perception so the item is shoved aside no matter
		// which way a creature bumps into it (an item has no meaningful heading).
		LOS_FOV = Math.PI * 2;
		LOS_RANGE = 2;
		SEARCH_FREQ = 5;
		switch (kind) {
		case FOOD:
			size = 4;
			health = 4; // a light poke destroys it
			col = new Color(0xC8402E);
			break;
		case CRATE:
			size = 8;
			health = 20; // durability: several hits to break
			col = new Color(0x9C6B3C);
			break;
		case HAZARD:
			size = 6;
			health = 40;
			col = new Color(0x7A2E8A);
			break;
		}
	}

	// ---- factories ---------------------------------------------------------

	public static Item food(double x, double y, double z) {
		return new Item(x, y, z, Kind.FOOD);
	}

	public static Item crate(double x, double y, double z) {
		return new Item(x, y, z, Kind.CRATE);
	}

	public static Item hazard(double x, double y, double z) {
		return new Item(x, y, z, Kind.HAZARD);
	}

	// ---- fluent attributes -------------------------------------------------

	public Item withSize(int s) {
		size = s;
		return this;
	}

	public Item withFoodEnergy(double e) {
		foodEnergy = e;
		return this;
	}

	public Item withDurability(int h) {
		health = h;
		return this;
	}

	public Item withSpill(int n) {
		spill = n;
		return this;
	}

	public Item withHazardDamage(int d) {
		hazardDamage = d;
		return this;
	}

	// ---- state -------------------------------------------------------------

	public Kind getKind() {
		return kind;
	}

	public double getFoodEnergy() {
		return foodEnergy;
	}

	/** Whether a creature can eat this item at all (a crate cannot be eaten). */
	public boolean isEdible() {
		return kind == Kind.FOOD || kind == Kind.HAZARD;
	}

	/**
	 * A signed hint for the item sense: {@code +1} food (worth eating),
	 * {@code -1} hazard (worth avoiding), {@code 0} crate (inert). Presence is
	 * carried by {@link AgentIO#S_ITEM_PROX}, so 0 here is unambiguous.
	 */
	public double kindSignal() {
		switch (kind) {
		case FOOD:
			return 1.0;
		case HAZARD:
			return -1.0;
		default:
			return 0.0;
		}
	}

	// ---- interactions ------------------------------------------------------

	/**
	 * A creature eats this item. FOOD feeds the eater and is consumed; a HAZARD
	 * is swallowed but bites back, damaging the eater; a CRATE is inedible and
	 * ignored. Returns true if anything was actually eaten.
	 */
	public boolean beEatenBy(NPC eater) {
		if (isRemoved()) {
			return false;
		}
		switch (kind) {
		case FOOD:
			eater.addEnergy(foodEnergy);
			remove();
			return true;
		case HAZARD:
			eater.damage(hazardDamage); // poisonous: harms the eater
			remove();
			return true;
		default:
			return false; // a crate is not food
		}
	}

	/**
	 * A creature attacks this item. Every kind takes the hit; a HAZARD also
	 * wounds the attacker (spikes), and a CRATE that is beaten down to zero
	 * durability <b>shatters</b>, scattering {@link #spill} food items where it
	 * stood before it is removed.
	 */
	public void beAttackedBy(NPC attacker, int dmg) {
		if (isRemoved()) {
			return;
		}
		if (kind == Kind.HAZARD) {
			attacker.damage(hazardDamage); // striking a spiky/thorny object hurts
		}
		damage(dmg);
		if (kind == Kind.CRATE && health <= 0 && !broken) {
			shatter();
		}
	}

	/** Scatters this crate's contents as loose food, then removes the crate. */
	private void shatter() {
		broken = true;
		// Deterministic ring of food around the crate's spot (no RNG): evenly
		// spaced so a broken crate reads as a burst of loot.
		for (int i = 0; i < spill; i++) {
			double a = (Math.PI * 2 * i) / Math.max(1, spill);
			double r = getSize() * 0.6 + 0.15;
			Item f = Item.food(X + r * Math.cos(a), Y + r * Math.sin(a), Z);
			getWorld().spawnEntity(f);
		}
		remove();
	}

	// ---- lifecycle: no thinking -------------------------------------------

	@Override
	protected void think() {
		// Inanimate: an item makes no decisions. It is still perceived, collided
		// with and shoved aside (handled by NPC.collisionCheck via its neighbour
		// scan), but it never chooses to move or act.
	}

	@Override
	protected void draw(Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		int cx = pixelX(v, 0), cy = pixelY(v, 0);
		int r = Math.max(2, round(Utils.scaleZ2((int) Z, v.getCamZ(), size) * 2));
		switch (kind) {
		case FOOD:
			drawFood(g2, cx, cy, r);
			break;
		case CRATE:
			drawCrate(g2, cx, cy, r);
			break;
		case HAZARD:
			drawHazard(g2, cx, cy, r);
			break;
		}
	}

	/** A round fruit: red body, a soft highlight and a little green leaf. */
	private void drawFood(Graphics2D g2, int cx, int cy, int r) {
		g2.setColor(new Color(0x30, 0x18, 0x10, 90));
		g2.fillOval(cx - r + 1, cy - r + 2, r * 2, r * 2); // shadow
		g2.setColor(new Color(0xC8, 0x40, 0x2E));
		g2.fillOval(cx - r, cy - r, r * 2, r * 2);
		g2.setColor(new Color(0xF2, 0x8A, 0x6A, 180));
		g2.fillOval(cx - r + r / 3, cy - r + r / 4, Math.max(1, r), Math.max(1, r)); // sheen
		g2.setColor(new Color(0x4C, 0x8A, 0x33));
		g2.fillOval(cx, cy - r - r / 2, Math.max(2, r), Math.max(2, r / 2)); // leaf
	}

	/** A wooden crate: brown box, plank seams, a darker rim. */
	private void drawCrate(Graphics2D g2, int cx, int cy, int r) {
		int s = r * 2;
		g2.setColor(new Color(0x2a, 0x1c, 0x10, 90));
		g2.fillRect(cx - r + 1, cy - r + 2, s, s); // shadow
		g2.setColor(new Color(0x9C, 0x6B, 0x3C));
		g2.fillRect(cx - r, cy - r, s, s);
		g2.setColor(new Color(0x6E, 0x48, 0x24));
		g2.setStroke(new BasicStroke(Math.max(1, r / 4f)));
		g2.drawRect(cx - r, cy - r, s, s); // frame
		g2.drawLine(cx - r, cy - r, cx + r, cy + r); // diagonal braces
		g2.drawLine(cx + r, cy - r, cx - r, cy + r);
		g2.setColor(new Color(0xC2, 0x92, 0x5C, 160));
		g2.drawLine(cx - r + 1, cy - r + 1, cx + r - 1, cy - r + 1); // top plank sheen
	}

	/** A hazard: a dark spiky burr with red-tipped points -- reads as "don't". */
	private void drawHazard(Graphics2D g2, int cx, int cy, int r) {
		int spikes = 8;
		int[] xs = new int[spikes * 2];
		int[] ys = new int[spikes * 2];
		for (int i = 0; i < spikes * 2; i++) {
			double a = Math.PI * i / spikes;
			double rad = (i % 2 == 0) ? r : r * 0.45;
			xs[i] = cx + (int) Math.round(rad * Math.cos(a));
			ys[i] = cy + (int) Math.round(rad * Math.sin(a));
		}
		g2.setColor(new Color(0x2a, 0x0f, 0x2f, 90));
		g2.fillOval(cx - r + 1, cy - r + 2, r * 2, r * 2); // shadow
		g2.setColor(new Color(0x4A, 0x1C, 0x55));
		g2.fillPolygon(xs, ys, xs.length);
		g2.setColor(new Color(0xD8, 0x3A, 0x4A));
		g2.setStroke(new BasicStroke(Math.max(1, r / 5f)));
		g2.drawPolygon(xs, ys, xs.length); // red-tipped spikes
	}

	@Override
	public String getNpcTypeName() {
		return "Item";
	}
}
