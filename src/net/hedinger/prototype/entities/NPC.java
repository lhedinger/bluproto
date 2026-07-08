package net.hedinger.prototype.entities;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.util.Stack;
import java.util.TreeMap;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.ResourceManager;
import net.hedinger.prototype.engine.Scent;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.View.ViewMode;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.main.PrototypeWorld;

public abstract class NPC extends Entity {
	// misc. variables
	protected boolean drawLine;
	protected boolean drawTrace;
	protected boolean drawPing;
	protected boolean drawLOS;

	// targeting variables
	protected double tX, tY, tZ;
	protected int SEARCH_FREQ;
	protected double LOS_RANGE; // max distance entity can see (tiles)
	protected double LOS_FOV; // max field of view (radians)
	protected int status;
	protected int size;
	protected Color col = Color.ORANGE;

	private int blink_random = 0;
	private int blink_on = 50;
	private int blink_off = 5;
	private float ping = 0;

	private String message = "";
	private int message_fade = 0;
	private int mesage_fade_max = 0;

	// pathfinding variabels
	protected Stack<Integer> path;
	protected int path_next;
	protected int path_goal;

	protected int hostile = 2;

	protected int detected = 0;

	protected Entity grabbing = null;

	protected boolean flying = false;

	// ==========================================================
	// ECOSYSTEM STATE: needs, genome, homing, disease
	// ==========================================================

	/** opts this animal into the needs system (energy/hydration/breeding) */
	protected boolean metabolic = false;
	/** can catch disease through contact */
	protected boolean susceptible = false;

	protected double energy = 70, energyMax = 100;
	protected double hydration = 70, hydrationMax = 100;
	protected double metabolism = 0.02; // baseline energy drain per tick
	protected double thirstRate = 0.012;

	protected Genome genome = null;
	protected int matureAge = 500;
	protected int litterMax = 2;
	protected boolean pregnant = false;
	protected int gestation = 0;
	protected Genome mateGenome = null;
	protected int matingCooldown = 0;

	protected Scent trailScent = null; // deposited every tick while alive
	protected Nest nest = null;
	protected double carriedFood = 0; // biomass being hauled home

	protected int infection = 0; // >0 = sick, counts down to recovery
	protected boolean immune = false;

	// remembered locations (home-range knowledge)
	protected double waterX = -1, waterY = -1;
	protected double forageX = -1, forageY = -1;

	protected static final double MOVE_COST = 0.5; // energy per tile moved
	protected static final double FLORA_ENERGY = 55; // energy per unit flora

	protected TreeMap<Double, NPC> targets = new TreeMap<Double, NPC>();
	protected TreeMap<Double, NPC> focusTargets = new TreeMap<Double, NPC>();

	public NPC(double x, double y, double z) {
		super(x, y, z);
		initialize();
	}

	protected NPC(double x, double y, double z, double d) {
		super(x, y, z);
		initialize();
	}

	private void initialize() {
		tX = X;
		tY = Y;
		tZ = Z;

		size = 6;
		// col = new Color(150, 150, 150);
		drawLine = false;
		drawTrace = false;
		drawPing = false;
		drawLOS = false;

		path = null;
		path_next = -1;
		path_goal = -1;
		SEARCH_FREQ = 50;
		LOS_RANGE = 5;
		LOS_FOV = Math.PI; // entity can see 180 degrees left and right

		status = NPC.STATUS_IDLE;

		if (D >= 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}

		blink_random = (int) (Math.random() * 0);
	}

	@Override
	protected void run_extended() {
		ecosystemPreTick();

		PrototypeWorld.stopwatch.start();
		targets = scanTargets(targets);
		PrototypeWorld.stopwatch.stop();

		ecosystemPostTick();

		if (status < 0 || status > 3) {
			status = NPC.STATUS_IDLE;
		}

		if (path != null) {
			if (path.size() == 0) {
				path = null;
			}
		}

		if (D >= 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}

	}

	@Override
	protected void draw_dead(Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;

		g2.drawImage(ResourceManager.getCropseSprite(hostile),
				pixelX(v, size * 2),
				pixelY(v, size * 2),
				size * 4,
				size * 4, null);

	}

	@Override
	protected void draw(Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		int width = (int) g.getClipBounds().getMaxX();
		int height = (int) g.getClipBounds().getMaxY();

		if (drawLOS && v.getViewMode() == ViewMode.ALL) {
			g2.setStroke(new BasicStroke(2));
			g2.setColor(new Color(250, 250, 250, 100));
			int x = pixelX(v, 0);
			int y = pixelY(v, 0);
			int r = toPixel(v, LOS_RANGE);
			g2.drawOval(x - r, y - r, r * 2, r * 2);
			if (LOS_FOV >= Math.PI - 0.0001) {
				g2.drawLine(round(x + 32 * Math.cos(D)), round(y + 32 * Math.sin(D)), round(x + r
						* Math.cos(D)), round(y + r * Math.sin(D)));
			} else {
				g2.drawLine(round(x + 32 * Math.cos(D - LOS_FOV)), round(y + 32
						* Math.sin(D - LOS_FOV)), round(x + r * Math.cos(D - LOS_FOV)), round(y + r
								* Math.sin(D - LOS_FOV)));
				g2.drawLine(round(x + 32 * Math.cos(D + LOS_FOV)), round(y + 32
						* Math.sin(D + LOS_FOV)), round(x + r * Math.cos(D + LOS_FOV)), round(y + r
								* Math.sin(D + LOS_FOV)));
			}
		}
		if (drawPing) {
			g2.setStroke(new BasicStroke(2));
			g2.setColor(new Color(250, 250, 250, 100));
			if (ping > 0) {
				for (int i = 0; i < 10; i++) {
					g2.setColor(new Color(0, 255, 0, 5 * i));
					g2.drawOval(pixelX(v, toPixel(v, ping)) - i, pixelY(v, toPixel(v, ping)) - i,
							toPixel(v, ping * 2) + i * 2, toPixel(v, ping * 2) + i * 2);
				}
			}
			ping += 0.3;
			if (ping > LOS_RANGE) {
				ping = -SEARCH_FREQ;
			}
		}

		if (v.getViewMode() == ViewMode.ALL && this.getNpcTypeName() == "Human") {
			g2.setColor(new Color(255, 255, 255, 50));
			for (NPC e : targets.values()) {
				if (e != null) {
					g2.drawLine(
							e.pixelX(v, 0),
							e.pixelY(v, 0),
							pixelX(v, 0),
							pixelY(v, 0));
				}
			}

			g2.setColor(new Color(255, 100, 100, 140));
			for (NPC e : focusTargets.values()) {
				if (e != null) {
					g2.drawLine(
							e.pixelX(v, 0),
							e.pixelY(v, 0),
							pixelX(v, 0),
							pixelY(v, 0));
				}
			}
		}

		g2.setColor(col);
		// g2.fillOval(pixelX(v, size * 0.5), pixelY(v, size * 0.5), size,
		// size);
		g2.setStroke(new BasicStroke(1));

		if (drawLine) {
			float ts = Utils.scaleZ((int) Z, v.getCamZ());
			g2.drawLine(round((X - v.getCamX()) * ts + width / 2), (int) Math
					.round((Y - v.getCamY()) * ts + height / 2), round(size * Math.cos(D)
							+ round((X - v.getCamX()) * ts + width / 2)),
					round(size * Math.sin(D)
							+ Math.round((Y - v.getCamY()) * ts + height / 2)));
		}
		g2.setStroke(new BasicStroke(1));
		g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 100));
		if (drawTrace) {
			float ts = Utils.scaleZ((int) Z, v.getCamZ());
			g2.drawLine(round((X - v.getCamX()) * ts + width / 2), (int) Math
					.round((Y - v.getCamY()) * ts + height / 2), (int) Math
					.round((tX - v.getCamX()) * ts + width / 2),
					(int) Math
					.round((tY - v.getCamY()) * ts + height / 2));
		}

		float relativeSize = Utils.scaleZ2((int) Z, v.getCamZ(), size);
		int relativeSize2 = round(relativeSize * 2);

		g2.drawImage(ResourceManager.getNpcSprite(hostile), pixelX(v, relativeSize2), pixelY(v,
				relativeSize2), relativeSize2 * 2, relativeSize2 * 2, null);

		if (message_fade > 0) {
			g2.setFont(new Font("Arial", Font.BOLD, 10));
			g2.setColor(new Color(250, 250, 250));
			if (message_fade < mesage_fade_max) {
				float alpha = message_fade;
				alpha = alpha / mesage_fade_max;
				alpha = alpha * 250;

				g2.setColor(new Color(250, 250, 250, Math.round(alpha)));
			}

			FontMetrics fm = g.getFontMetrics();
			Rectangle2D textsize = fm.getStringBounds(message, g);

			float ts = Utils.scaleZ((int) Z, v.getCamZ());
			g.drawString(message, round((X - v.getCamX()) * ts + width / 2 - size * 0.5
					- textsize.getWidth() * 0.5), round((Y - v.getCamY()) * ts + height
							/ 2 - size * 0.5 - 5));

			message_fade--;
		}
	}

	@Override
	protected void think() {
		// to be overwritten by other entities
		// ...this is for the generic type:

		// tX = getWorld().getMouseX();
		// tY = getWorld().getMouseY();
		// tZ = getWorld().getMouseZ();

	}

	@Override
	public void kill() {
		age = -1;
	}

	@Override
	public boolean isFlying() {
		return flying;
	}

	@Override
	public void collisionCheck() {
		float spring = 0.25f;
		for (NPC npc : targets.values()) {
			double dx = npc.getX() - getX();
			double dy = npc.getY() - getY();
			double distance = Math.sqrt(dx * dx + dy * dy);
			if (canTouch(npc)) {
				double angle = Math.atan2(-dy, -dx);
				double targetX = Math.cos(angle) * distance;
				double targetY = Math.sin(angle) * distance;
				double ax = (targetX) * spring;
				double ay = (targetY) * spring;
				dX += ax;
				dY += ay;

				// disease spreads through contact in dense groups
				if (susceptible && !immune && infection == 0 && npc.isInfected()
						&& Math.random() * 60 < 1) {
					infect();
				}
			}
		}
	}

	// |///////////////////////////////
	// |///////////////////////////////////////////////////////////////
	// LOS METHODS
	// |///////////////////////////////////////////////////////////////
	// |///////////////////////////////

	protected boolean canTouch(Entity e) {
		double distance = distance(e);
		double minDist = e.getSize() / 2 + getSize() / 2;
		return (distance < minDist);
	}

	protected boolean isInLOS() {
		return getWorld().hasLOS(X, Y, Z, D, tX, tY, tZ, LOS_RANGE, LOS_FOV);
	}

	protected boolean isInLOS(Entity e) {
		if (e == null) {
			return false;
		}

		return getWorld().hasLOS(X, Y, Z, D, e.getX(), e.getY(), e.getZ(), LOS_RANGE, LOS_FOV);
	}

	protected boolean isInLOS(double dist, double fov) {
		return getWorld().hasLOS(X, Y, Z, D, tX, tY, tZ, dist, fov);
	}

	protected boolean isValidMoveDestination() {
		if (!isFlying() && !getWorld().getTile(tX, tY, tZ).isWalkable()) {
			return false;
		}
		if (isFlying() && !getWorld().getTile(tX, tY, tZ).isFlyable()) {
			return false;
		}
		return getWorld().hasLOS(X, Y, Z, D, tX, tY, tZ, 99, Math.PI);
	}

	protected boolean isInLOS(double x, double y, double z) {
		return getWorld().hasLOS(X, Y, Z, D, x, y, z, LOS_RANGE, LOS_FOV);
	}

	protected boolean isInLOS(double x, double y, double z, double dist, double fov) {
		return getWorld().hasLOS(X, Y, Z, D, x, y, z, dist, fov);
	}

	// |///////////////////////////////
	// |///////////////////////////////////////////////////////////////
	// MOVEMENT METHODS
	// |///////////////////////////////////////////////////////////////
	// |///////////////////////////////

	protected void roam(double speed, int turn) {
		boolean bool = isValidMoveDestination();

		if (!bool || tZ != Z) {
			tX = X;
			tY = Y;
			tZ = Z;
			dX = 0;
			dY = 0;
			dZ = 0;
		}

		if (isColliding()) {
			tX = X;
			tY = Y;
			tZ = Z;
		}

		if (distance() < 0.05) {
			double d = 0.5 + Math.random() * 0.7;
			double a = variation(D, Math.PI * 0.5);
			if (Math.random() * 4 < 1) {
				a = Math.random() * 2 * Math.PI;
			}

			tX = X + d * Math.cos(a);
			tY = Y + d * Math.sin(a);
			tZ = Z;

		} else {
			chase(speed, turn);
		}

	}

	protected void roam(double speed, int turn, double direction) {
		boolean bool = isValidMoveDestination();

		if (!bool || tZ != Z) {
			tX = X;
			tY = Y;
			tZ = Z;
			dX = 0;
			dY = 0;
			dZ = 0;
		}

		if (isColliding()) {
			tX = X;
			tY = Y;
			tZ = Z;
		}

		if (distance() < 0.05) {
			double d = 0.5 + Math.random() * 3;
			double a = variation(direction, Math.PI * 0.25);
			if (Math.random() * 10 < 1) {
				a = Math.random() * 2 * Math.PI;
			}

			tX = X + d * Math.cos(a);
			tY = Y + d * Math.sin(a);
			tZ = Z;

		} else {
			chase(speed, turn);
		}

	}

	protected boolean chase(double speed, int turn) {
		if (!isInLOS(tX, tY, tZ, -1, Math.PI)) {
			dX = 0;
			dY = 0;
			dZ = 0;
			return false;
		}
		double angle = Math.atan2(Y - tY, X - tX) + Math.PI;

		if (D >= 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}

		if (angle > 2 * Math.PI) {
			angle -= 2 * Math.PI;
		}
		if (angle < 0) {
			angle += 2 * Math.PI;
		}

		double dA = angle - D;

		if (dA > Math.PI) {
			dA = -2 * Math.PI + dA;
		}
		if (dA < -Math.PI) {
			dA = 2 * Math.PI + dA;
		}

		if (Math.abs(dA) < Math.PI * 0.05f) {
			D = angle;
		} else if (dA > 0) {
			D += (Math.sqrt(Math.abs(dA)) / turn);
		} else if (dA < 0) {
			D -= (Math.sqrt(Math.abs(dA)) / turn);
		}

		if (Math.abs(dA) > Math.PI * 0.25) {
			dX = 0;
			dY = 0;
			dZ = 0;
		} else {
			move(speed, D);
		}
		return true;
	}

	protected boolean follow(double speed, int turn, Entity e, double radius) {
		if (e == null) {
			return false;
		}

		if (!isInLOS(e.getX(), e.getY(), e.getZ(), -1, Math.PI)) {
			dX = 0;
			dY = 0;
			dZ = 0;
			return false;
		}
		double angle = Math.atan2(Y - tY, X - tX) + Math.PI;

		if (D >= 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}

		if (angle > 2 * Math.PI) {
			angle -= 2 * Math.PI;
		}
		if (angle < 0) {
			angle += 2 * Math.PI;
		}

		double dA = angle - D;

		if (dA > Math.PI) {
			dA = -2 * Math.PI + dA;
		}
		if (dA < -Math.PI) {
			dA = 2 * Math.PI + dA;
		}

		if (dA > 0) {
			D += (Math.sqrt(Math.abs(dA)) / turn);
		} else if (dA < 0) {
			D -= (Math.sqrt(Math.abs(dA)) / turn);
		}

		if (Math.abs(dA) > Math.PI * 0.5) {
			dX = 0;
			dY = 0;
			dZ = 0;
		}
		if (distance(e.getX(), e.getY(), e.getZ()) < radius) {
			roam(speed, turn);
		} else {
			move(speed, D);
		}
		return true;
	}

	protected boolean flee(double speed, int turn, Entity e, double radius) {

		// panic is contagious: leave a fear deposit others of our kind smell
		if (getWorld() != null && getWorld().isValid(X, Y, Z)) {
			getWorld().getTile(X, Y, Z).addScent(Scent.FEAR, 0.6f);
		}

		// escape vector points from the threat through us; fall back to the
		// last locked target position when no live threat is given
		double away;
		if (e != null) {
			away = Math.atan2(Y - e.getY(), X - e.getX());
		} else {
			away = Math.atan2(Y - tY, X - tX);
		}
		away = World.fixAngle(away);

		roam(speed, turn, away);

		return true;
	}

	protected void turn(double speed, int turn) {
		double angle = Math.atan2(Y - tY, X - tX) + Math.PI;

		if (D >= 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}

		if (angle > 2 * Math.PI) {
			angle -= 2 * Math.PI;
		}
		if (angle < 0) {
			angle += 2 * Math.PI;
		}

		double dA = angle - D;

		if (dA > Math.PI) {
			dA = -2 * Math.PI + dA;
		}
		if (dA < -Math.PI) {
			dA = 2 * Math.PI + dA;
		}

		if (dA > 0) {
			D += (Math.sqrt(Math.abs(dA)) / turn);
		} else if (dA < 0) {
			D -= (Math.sqrt(Math.abs(dA)) / turn);
		}
	}

	int backup_collide = -1;

	protected void backup(double speed) {
		if (backup_collide > 400) {
			backup_collide = -1;
		}

		if (backup_collide == -1) {
			if (D > 2 * Math.PI) {
				D -= 2 * Math.PI;
			}
			if (D < 0) {
				D += 2 * Math.PI;
			}
		} else if (backup_collide == 0) {
			D = Math.random() * Math.PI * 2;
			backup_collide = 1;
		} else {
			backup_collide++;
		}

		dX = speed * Math.cos(D + Math.PI);
		dY = speed * Math.sin(D + Math.PI);

		dX = variation(dX, dX * 0.1);
		dY = variation(dY, dY * 0.1);

		if (isColliding()) {
			backup_collide = 0;
		}

	}

	protected void move(double speed) {
		move(speed, D);
	}

	protected void move(double speed, double dir) {

		D = dir;

		if (D > 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}

		dX = speed * Math.cos(D);
		dY = speed * Math.sin(D);

		dX = variation(dX, dX * 0.1);
		dY = variation(dY, dY * 0.1);

		if (metabolic) {
			energy = Math.max(0, energy - Math.abs(speed) * MOVE_COST);
			// wading slows you down
			if (getWorld() != null && getWorld().getTile(X, Y, Z).isWater()) {
				dX *= 0.6;
				dY *= 0.6;
			}
		}
	}

	// |///////////////////////////////
	// NAVIGATION METHODS
	// |///////////////////////////////

	protected void generatePath(double x, double y, double z) {
		path = getWorld().findPath(X, Y, Z, x, y, z);
		if (path.size() == 0) {
			path = null;
		}
	}

	protected boolean followPath(double speed, int turn) {
		if (path == null) {
			dX = 0;
			dY = 0;
			dZ = 0;
			return false;
		}
		if (path.size() == 0) {
			path = null;
			return true;
		}

		if (getWorld().distance(X, Y, Z, tX, tY, tZ) < 0.2) {
			int next = path.pop();
			int c = getWorld().hashCol(next);
			int r = getWorld().hashRow(next);
			int l = getWorld().hashLvl(next);

			tX = c + variation(0.5, 0.2);
			tY = r + variation(0.5, 0.2);
			tZ = l;

			dX = 0;
			dY = 0;
			dZ = 0;
			return true;
		}

		return chase(speed, turn);
	}

	protected boolean followPath2(double speed, int turn) {
		if (path == null) {
			dX = 0;
			dY = 0;
			dZ = 0;
			return false;
		}
		if (path.size() == 0) {
			path = null;
			return false;
		}

		if (getWorld().distance(X, Y, Z, tX, tY, tZ) < 0.5) {
			int next = path.pop();

			int c = getWorld().hashCol(next);
			int r = getWorld().hashRow(next);
			int l = getWorld().hashLvl(next);

			if (path.size() < 2) {
				tX = c + variation(0.5, 0.25);
				tY = r + variation(0.5, 0.25);
			} else {
				tX = c + variation(0.5, 0.005);
				tY = r + variation(0.5, 0.005);
			}
			tZ = l;

			dX = 0;
			dY = 0;
			dZ = 0;
			return true;
		}

		return chase(speed, turn);

	}

	// |///////////////////////////////
	// |///////////////////////////////////////////////////////////////
	// TARGET METHODS
	// |///////////////////////////////////////////////////////////////
	// |///////////////////////////////

	protected boolean lockTarget(NPC target, double variation) {
		if (target == null) {
			return false;
		}

		tX = target.getX() + variation(0, variation);
		tY = target.getY() + variation(0, variation);
		tZ = target.getZ();

		return true;
	}

	protected boolean lockTarget(NPC target) {
		if (target == null) {
			return false;
		}

		tX = target.getX();
		tY = target.getY();
		tZ = target.getZ();

		return true;
	}

	protected NPC getClosestNPC(TreeMap<Double, NPC> list) {
		if (list == null) {
			return null;
		}
		if (list.isEmpty()) {
			return null;
		}

		return list.firstEntry().getValue();
	}

	protected NPC getClosestNPC(TreeMap<Double, NPC> list, int stat) {
		if (list == null) {
			return null;
		}

		for (NPC e : list.values()) {
			if (e != null) {
				if (e.getStatus() == stat) {
					return e;
				}
			}
		}

		return null;
	}

	protected NPC getClosestNPC(TreeMap<Double, NPC> list, int age, boolean older) {
		if (list == null) {
			return null;
		}

		for (NPC e : list.values()) {
			if (e != null) {
				if (older && e.getAge() > age) {
					return e;
				}
				if (!older && e.getAge() < age) {
					return e;
				}
			}
		}

		return null;
	}

	/**
	 * checks to see if target is dead
	 *
	 * @param t
	 *            target entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @return
	 */
	protected boolean killedTarget(NPC t, double range, double fov) {
		if (t == null) {
			return false;
		}

		if (t.isDead()) {
			return true;
		}

		return false;
	}

	/**
	 * checks to see if target is outside seeker LOS
	 *
	 * @param t
	 *            target entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @return
	 */
	protected boolean lostTarget(NPC t, double range, double fov) {
		if (t == null) {
			return false;
		}

		if (!isInLOS(t.getX(), t.getY(), t.getZ(), range, fov)) {
			return true;
		}

		return false;
	}

	/**
	 * checks to see if target is not null, alive, and of valid type
	 *
	 * @param t
	 *            target Entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param types
	 *            valid entity types
	 * @return if Entity t is a valid target
	 */
	protected boolean validTarget(NPC t, double range, double fov, String type, boolean include) {
		String[] types = new String[1];
		types[0] = type;

		if (t == null) {
			return false;
		}

		if (t.isDead()) {
			return false;
		}

		if (!World.includesType(t.getEntityTypeName(), types) && include) {
			return false;
		}

		if (!World.excludesType(t.getEntityTypeName(), types) && !include) {
			return false;
		}

		return true;
	}

	/**
	 * checks to see if target is not null, alive, and of valid type
	 *
	 * @param t
	 *            target Entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param types
	 *            valid entity types
	 * @return if Entity t is a valid target
	 */
	protected boolean validTarget(NPC t, double range, double fov, String[] types, boolean include) {
		if (t == null) {
			return false;
		}

		if (t.isDead()) {
			return false;
		}

		if (!World.filterType(t.getEntityTypeName(), types, include)) {
			return false;
		}

		return true;
	}

	/**
	 * checks to see if target is not null, alive, in LOS, and of valid type.
	 * Uses entity variables for the LOS.
	 *
	 * @param t
	 *            target Entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param types
	 *            valid entity types
	 * @return if Entity t is a valid target
	 */
	protected boolean seeTarget(NPC t, String type, boolean include) {
		String[] types = new String[1];
		types[0] = type;

		return seeTarget(t, LOS_RANGE, LOS_FOV, types, include);
	}

	/**
	 * checks to see if target is not null, alive, in LOS, and of valid type.
	 *
	 * @param t
	 *            target Entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param types
	 *            valid entity types
	 * @return if Entity t is a valid target
	 */
	protected boolean seeTarget(NPC t, double range, double fov, String type, boolean include) {
		String[] types = new String[1];
		types[0] = type;

		return seeTarget(t, range, fov, types, include);
	}

	/**
	 * checks to see if target is not null, alive, in LOS, and of valid type.
	 * Uses entity variables for the LOS.
	 *
	 * @param t
	 *            target Entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param types
	 *            valid entity types
	 * @return if Entity t is a valid target
	 */
	protected boolean seeTarget(NPC t, String[] types, boolean include) {
		return seeTarget(t, LOS_RANGE, LOS_FOV, types, include);
	}

	/**
	 * checks to see if target is not null, alive, in LOS, and of valid type.
	 * Uses entity variables for the LOS.
	 *
	 * @param t
	 *            target Entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param types
	 *            valid entity types
	 * @return if Entity t is a valid target
	 */
	protected boolean seeTarget(NPC t, double range, double fov, String[] types, boolean include) {
		if (!validTarget(t, range, fov, types, include)) {
			return false;
		}

		if (!isInLOS(t.getX(), t.getY(), t.getZ(), range, fov)) {
			return false;
		}

		return true;
	}

	protected boolean seeTarget(NPC t, double range, double fov) {
		if (t == null) {
			return false;
		}
		if (t.isDead()) {
			return false;
		}

		if (!isInLOS(t.getX(), t.getY(), t.getZ(), range, fov)) {
			return false;
		}

		return true;
	}

	private TreeMap<Double, NPC> scanTargets(TreeMap<Double, NPC> ts) {
		TreeMap<Double, NPC> temp = new TreeMap<Double, NPC>();

		if (Math.random() * SEARCH_FREQ < 1) {
			return getWorld().searchNPC3(X, Y, Z, D, LOS_RANGE, LOS_FOV, getID());
		}

		if (ts != null) {
			if (ts.size() > 0) {
				temp.putAll(ts);
			}
		}

		TreeMap<Double, NPC> output = new TreeMap<Double, NPC>();

		for (NPC e : temp.values()) {
			if (seeTarget(e, LOS_RANGE, LOS_FOV, "", false)) {
				if (isFriendly() && e.isHostile()) {
					if (!isDead() && !e.isDead()) {
						e.mark();
					}
				}

				if (e != null) {
					output.put(distance(e.getX(), e.getY(), e.getZ()), e);
				}
			}
		}

		return output;
	}

	/**
	 * updates, validates and returns a new target list that matches LOS and
	 * Entity type paramters. SF IS ENABLED. Uses entity variables for the
	 * Search Frequency and LOS.
	 *
	 * @param ts
	 *            old target list
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param type
	 *            valid entity type
	 * @return updated target list
	 */
	protected TreeMap<Double, NPC> getTargets(TreeMap<Double, NPC> ts, String type, boolean include) {
		String[] types = new String[1];
		types[0] = type;

		return getTargets(ts, types, include);
	}

	/**
	 * updates, validates and returns a new target list that matches LOS and
	 * Entity type paramters. SF IS ENABLED
	 *
	 * @param ts
	 *            old target list
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param type
	 *            valid entity type
	 * @return updated target list
	 */
	protected TreeMap<Double, NPC> getTargets(int sf, TreeMap<Double, NPC> ts, double range,
			double fov, String type, boolean include) {
		String[] types = new String[1];
		types[0] = type;

		return getTargets(sf, ts, range, fov, types, include);
	}

	/**
	 * updates, validates and returns a new target list that matches LOS and
	 * Entity type paramters. SF IS ENABLED. Uses entity variables for the
	 * Search Frequency and LOS.
	 *
	 * @param ts
	 *            old target list
	 * @param type
	 *            valid entity types
	 * @return updated target list
	 */
	protected TreeMap<Double, NPC> getTargets(TreeMap<Double, NPC> ts, String[] types,
			boolean include) {
		return getTargets(SEARCH_FREQ, ts, LOS_RANGE, LOS_FOV, types, include);
	}

	/**
	 * updates, validates and returns a new target list that matches LOS and
	 * Entity type paramters. SF IS ENABLED
	 *
	 * @param sf
	 *            search frequency
	 * @param ts
	 *            old target list
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param type
	 *            valid entity types
	 * @return updated target list
	 */
	protected TreeMap<Double, NPC> getTargets(int sf, TreeMap<Double, NPC> ts, double range,
			double fov, String[] types, boolean include) {

		TreeMap<Double, NPC> temp = new TreeMap<Double, NPC>();

		if (ts != null) {
			// temp.putAll(ts);
		}

		temp.putAll(targets);

		TreeMap<Double, NPC> output = new TreeMap<Double, NPC>();

		for (NPC e : temp.values()) {
			if (isLegalTarget(e, range, fov, types, include)) {
				output.put(distance(e.getX(), e.getY(), e.getZ()), e);
			}
		}

		return output;
	}

	protected boolean isLegalTarget(NPC t, double range, double fov, String[] types, boolean include) {
		if (t == null) {
			return false;
		}
		if (t.isDead()) {
			return false;
		}
		if (range >= 0 && distance(t) > range) {
			return false;
		}
		if (!World.filterType(t.getEntityTypeName(), types, include)) {
			return false;
		}

		return true;
	}

	// ==========================================================
	// ECOSYSTEM TICKS
	// ==========================================================

	/** Runs before perception: vision scaling and trail marking. */
	private void ecosystemPreTick() {
		if (isDead() || getWorld() == null) {
			return;
		}

		// night narrows vision unless the genome is nocturnal
		if (genome != null) {
			double light = getWorld().getDaylight();
			double vision = 0.35 + 0.65 * Math.max(light, genome.nocturnality);
			LOS_RANGE = genome.losRange * vision;
		}

		if (trailScent != null && getWorld().isValid(X, Y, Z)) {
			getWorld().getTile(X, Y, Z).addScent(trailScent, 0.8f);
		}
	}

	/** Runs after perception: needs drain, gestation, sickness. */
	private void ecosystemPostTick() {
		if (!metabolic || isDead()) {
			return;
		}

		double drain = metabolism;
		if (status == STATUS_SLEEP) {
			drain *= 0.4;
		}
		if (infection > 0) {
			drain *= 1.6;
		}
		energy -= drain;
		hydration -= thirstRate;

		if (energy <= 0) {
			energy = 0;
			damage(1); // starving
		}
		if (hydration <= 0) {
			hydration = 0;
			damage(1); // dehydrated
		}

		if (matingCooldown > 0) {
			matingCooldown--;
		}
		if (pregnant) {
			gestation--;
			if (gestation <= 0) {
				birth();
			}
		}
		if (infection > 0) {
			infection--;
			if (Math.random() * 300 < 1) {
				damage(4);
			}
			if (infection == 0) {
				immune = true; // survived it
			}
		}
	}

	@Override
	public void damage(int dmg) {
		super.damage(dmg);
		// wounds leave a blood scent that predators can track
		if (dmg >= 5 && getWorld() != null && getWorld().isValid(X, Y, Z)) {
			getWorld().getTile(X, Y, Z).addScent(Scent.BLOOD, Math.min(3f, dmg * 0.05f));
		}
	}

	// ==========================================================
	// FORAGING VERBS: graze, drink, seek
	// ==========================================================

	/** Eats plant cover off the current tile. */
	protected boolean graze(double bite) {
		if (getWorld() == null || !getWorld().isValid(X, Y, Z)) {
			return false;
		}
		Tile t = getWorld().getTile(X, Y, Z);
		float eaten = t.consumeFlora((float) bite);
		if (eaten <= 0) {
			return false;
		}
		energy = Math.min(energyMax, energy + eaten * FLORA_ENERGY);
		if (t.getFlora() > 0.5f) {
			t.addScent(Scent.FOOD, 0.4f); // mark the rich patch
		}
		return true;
	}

	/** Drinks if standing on or next to water; remembers the waterhole. */
	protected boolean drink() {
		if (getWorld() == null) {
			return false;
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				if (getWorld().isValid(X + dx, Y + dy, Z)
						&& getWorld().getTile(X + dx, Y + dy, Z).isWater()) {
					hydration = hydrationMax;
					waterX = X + dx;
					waterY = Y + dy;
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Heads for water: drinks in place, walks to a remembered waterhole, or
	 * searches/wanders.
	 */
	protected boolean seekWater(double speed, int turn) {
		if (drink()) {
			tX = X;
			tY = Y;
			tZ = Z;
			move(0);
			return true;
		}
		if (waterX < 0 && Math.random() * 4 < 1) {
			findWater();
		}
		if (waterX >= 0) {
			navigate(speed, turn, waterX, waterY);
			return true;
		}
		// herds have been to water before: their trails lead somewhere useful
		if (!followScent(Scent.TRAIL_HERBIVORE, speed, turn)) {
			roam(speed, turn);
		}
		return false;
	}

	/** Scans surroundings for a water tile and memorizes it. */
	protected boolean findWater() {
		int r = (int) Math.ceil(Math.max(3, LOS_RANGE));
		double best = 9999;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				double sx = X + dx;
				double sy = Y + dy;
				if (!getWorld().isValid(sx, sy, Z)) {
					continue;
				}
				if (getWorld().getTile(sx, sy, Z).isWater()) {
					double d = Math.abs(dx) + Math.abs(dy);
					if (d < best) {
						best = d;
						waterX = (int) sx + 0.5;
						waterY = (int) sy + 0.5;
					}
				}
			}
		}
		return waterX >= 0;
	}

	/**
	 * Grazing loop: eat here if the tile is worth it, else walk to the best
	 * patch in sight, else follow food scent, else wander.
	 */
	protected boolean seekFlora(double speed, int turn, double bite) {
		if (getWorld() == null || !getWorld().isValid(X, Y, Z)) {
			return false;
		}

		if (getWorld().getTile(X, Y, Z).getFlora() > 0.12f) {
			tX = X;
			tY = Y;
			tZ = Z;
			move(0);
			graze(bite);
			forageX = -1;
			return true;
		}

		if (forageX >= 0) {
			if (!getWorld().isValid(forageX, forageY, Z)
					|| getWorld().getTile(forageX, forageY, Z).getFlora() < 0.1f) {
				forageX = -1; // patch is gone
			} else {
				navigate(speed, turn, forageX, forageY);
				return true;
			}
		}

		if (Math.random() * 5 < 1) {
			findFlora();
		}
		if (forageX < 0 && !followScent(Scent.FOOD, speed, turn)) {
			roam(speed, turn);
		}
		return false;
	}

	/** Scans surroundings for the best flora patch, preferring closer ones. */
	protected boolean findFlora() {
		int r = (int) Math.ceil(Math.max(2, LOS_RANGE));
		double best = 0.15;
		forageX = -1;
		forageY = -1;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				double sx = X + dx;
				double sy = Y + dy;
				if (!getWorld().isValid(sx, sy, Z)) {
					continue;
				}
				float flora = getWorld().getTile(sx, sy, Z).getFlora();
				if (flora > 0.15f) {
					double score = flora / (1 + 0.3 * (Math.abs(dx) + Math.abs(dy)));
					if (score > best) {
						best = score;
						forageX = (int) sx + 0.5;
						forageY = (int) sy + 0.5;
					}
				}
			}
		}
		return forageX >= 0;
	}

	// ==========================================================
	// SCENT VERBS: smell, track
	// ==========================================================

	/**
	 * Sniffs the surrounding tiles for the strongest deposit of a scent.
	 *
	 * @return direction (radians) toward the strongest gradient, or NaN if
	 *         nothing stands out
	 */
	protected double smell(Scent s) {
		if (getWorld() == null || !getWorld().isValid(X, Y, Z)) {
			return Double.NaN;
		}
		float best = getWorld().getTile(X, Y, Z).getScent(s) + 0.05f;
		double dir = Double.NaN;
		for (int i = 0; i < 8; i++) {
			double a = i * Math.PI / 4;
			double sx = X + Math.cos(a) * 1.2;
			double sy = Y + Math.sin(a) * 1.2;
			if (!getWorld().isValid(sx, sy, Z)) {
				continue;
			}
			Tile t = getWorld().getTile(sx, sy, Z);
			if (t.isSolid()) {
				continue;
			}
			if (t.getScent(s) > best) {
				best = t.getScent(s);
				dir = a;
			}
		}
		return dir;
	}

	/** Scent strength on the tile underfoot. */
	protected float senseScent(Scent s) {
		if (getWorld() == null || !getWorld().isValid(X, Y, Z)) {
			return 0;
		}
		return getWorld().getTile(X, Y, Z).getScent(s);
	}

	/** Walks up a scent gradient. @return false if there is no trail here */
	protected boolean followScent(Scent s, double speed, int turn) {
		double dir = smell(s);
		if (Double.isNaN(dir)) {
			return false;
		}
		tX = X + 1.5 * Math.cos(dir);
		tY = Y + 1.5 * Math.sin(dir);
		tZ = Z;
		return chase(speed, turn);
	}

	// ==========================================================
	// MOVEMENT/ACTION VERBS: navigate, rest, feed, carry
	// ==========================================================

	/**
	 * Goal-directed travel: straight-line chase when the goal is visible,
	 * otherwise pathfind, otherwise wander toward it.
	 */
	protected boolean navigate(double speed, int turn, double x, double y) {
		if (isInLOS(x, y, Z, -1, Math.PI)) {
			path = null;
			tX = x;
			tY = y;
			tZ = Z;
			return chase(speed, turn);
		}
		if (path == null && Math.random() * 15 < 1) {
			generatePath(x, y, Z);
		}
		if (path != null) {
			return followPath2(speed, turn);
		}
		roam(speed, turn);
		return false;
	}

	/** Sleep: cheap on energy, slowly heals a fed animal. */
	protected void rest() {
		status = STATUS_SLEEP;
		tX = X;
		tY = Y;
		tZ = Z;
		move(0);
		if (energy > 20 && health < 100 && Math.random() * 25 < 1) {
			health++;
		}
	}

	/** Eats from a carcass. Must be adjacent. */
	protected boolean feed(NPC corpse, double amount) {
		if (corpse == null || !corpse.isDead() || corpse.isRemoved()) {
			return false;
		}
		if (distance(corpse) > 0.9) {
			return false;
		}
		corpse.eat((int) Math.round(amount * 12)); // consumes the carcass
		energy = Math.min(energyMax, energy + amount);
		if (getWorld().isValid(corpse.getX(), corpse.getY(), corpse.getZ())) {
			getWorld().getTile(corpse.getX(), corpse.getY(), corpse.getZ())
					.addScent(Scent.BLOOD, 0.4f);
		}
		return true;
	}

	/**
	 * Feed from a carcass, and once sated start loading meat to haul home.
	 */
	protected boolean scavenge(NPC corpse, double bite, double carryMax) {
		if (corpse == null || !corpse.isDead() || corpse.isRemoved()) {
			return false;
		}
		if (distance(corpse) > 0.9) {
			return false;
		}
		if (energy < energyMax - bite) {
			return feed(corpse, bite);
		}
		if (carriedFood < carryMax) {
			corpse.eat((int) Math.round(bite * 12));
			carriedFood += bite;
			return true;
		}
		return false;
	}

	/** Finds the nearest carcass within range (any species). */
	protected NPC findCorpse(double range) {
		if (getWorld() == null) {
			return null;
		}
		NPC best = null;
		double bestDist = range;
		for (Integer id : getWorld().getRadialEntities(X, Y, Z, range)) {
			Entity e = getWorld().getEntity(id);
			if (e instanceof NPC && e.isDead() && e.getLvl() == (int) Z) {
				double d = distance(e);
				if (d <= bestDist) {
					bestDist = d;
					best = (NPC) e;
				}
			}
		}
		return best;
	}

	// ==========================================================
	// NEST VERBS: build, home, cache
	// ==========================================================

	/** Builds a nest on the current tile. One per animal. */
	protected boolean buildNest() {
		if (nest != null || getWorld() == null || !getWorld().isValid(X, Y, Z)) {
			return false;
		}
		Tile t = getWorld().getTile(X, Y, Z);
		if (!t.isWalkable() || t.isWater()) {
			return false;
		}
		Nest n = new Nest((int) X + 0.5, (int) Y + 0.5, (int) Z);
		if (getWorld().spawnEntity(n)) {
			nest = n;
			energy = Math.max(0, energy - 10);
			return true;
		}
		return false;
	}

	protected boolean hasNest() {
		return nest != null && !nest.isRemoved();
	}

	protected boolean atNest() {
		return hasNest() && distance(nest) < 0.8;
	}

	protected boolean goHome(double speed, int turn) {
		if (!hasNest()) {
			return false;
		}
		return navigate(speed, turn, nest.getX(), nest.getY());
	}

	/** Unloads carried food into the nest cache. */
	protected void cacheFood() {
		if (atNest() && carriedFood > 0) {
			nest.deposit(carriedFood);
			carriedFood = 0;
		}
	}

	/** Eats out of the nest cache. */
	protected boolean eatFromCache(double amount) {
		if (!atNest()) {
			return false;
		}
		double got = nest.withdraw(Math.min(amount, energyMax - energy));
		energy = Math.min(energyMax, energy + got);
		return got > 0;
	}

	// ==========================================================
	// REPRODUCTION: genome, mating, birth
	// ==========================================================

	/** Adopts a genome and maps it onto the working stats. */
	protected void applyGenome(Genome g) {
		genome = g;
		LOS_RANGE = g.losRange;
		metabolism = g.metabolism;
	}

	/**
	 * Courtship: approach the nearest willing packmate of the same species
	 * and mate on contact. The initiator carries the offspring.
	 */
	protected boolean seekMate(double speed, int turn) {
		NPC partner = null;
		for (NPC e : targets.values()) {
			if (e != null && !e.isDead() && e != this
					&& e.getNpcTypeName().equals(getNpcTypeName()) && e.canMate()) {
				partner = e;
				break;
			}
		}
		if (partner == null) {
			return false;
		}
		lockTarget(partner);
		if (distance(partner) > 0.7) {
			chase(speed, turn);
			return true;
		}
		mate(partner);
		return true;
	}

	protected void mate(NPC partner) {
		pregnant = true;
		gestation = (int) Math.max(100, genome != null ? genome.gestation : 400);
		mateGenome = (partner != null) ? partner.genome : null;
		energy = Math.max(0, energy - 15);
		matingCooldown = 800;
		if (partner != null) {
			partner.energy = Math.max(0, partner.energy - 10);
			partner.matingCooldown = 800;
		}
	}

	protected void birth() {
		pregnant = false;
		if (genome == null || getWorld() == null) {
			return;
		}
		int litter = 1 + Utils.random(Math.max(1, litterMax));
		for (int i = 0; i < litter; i++) {
			Genome g = genome.breed(mateGenome, 0.08);
			NPC child = createOffspring(variation(X, 0.3), variation(Y, 0.3), (int) Z, g);
			if (child != null && getWorld().spawnEntity(child)) {
				energy = Math.max(0, energy - 8);
			}
		}
		mateGenome = null;
	}

	/**
	 * Species hook: construct a newborn of your own kind carrying the given
	 * genome. Return null to opt out of reproduction.
	 */
	protected NPC createOffspring(double x, double y, double z, Genome g) {
		return null;
	}

	public void infect() {
		if (susceptible && !immune && infection == 0) {
			infection = 1500;
		}
	}

	public boolean isInfected() {
		return infection > 0;
	}

	public boolean isPregnant() {
		return pregnant;
	}

	public double getEnergy() {
		return energy;
	}

	public double getHydration() {
		return hydration;
	}

	public Genome getGenome() {
		return genome;
	}

	// ======================================================
	// PUBLIC GETTERS AND SETTERS PUBLIC GETTERS AND SETTERS
	// ======================================================

	public int getStatus() {
		return status;
	}

	public Color getColor() {
		return col;
	}

	public int getPixelSize() {
		return size;
	}

	@Override
	public float getSize() {
		return size / (float) ResourceManager.tileSize;
	}

	@Override
	public boolean isHostile() {
		return hostile == 2;
	}

	public boolean isFriendly() {
		return hostile == 0;
	}

	@Override
	public boolean isDetected() {
		return detected > 0;
	}

	/**
	 * An animal breeds only from surplus: mature, healthy, well-fed, not
	 * already carrying, and past its cooldown.
	 */
	public boolean canMate() {
		return metabolic && genome != null && !isDead() && !pregnant
				&& matingCooldown == 0 && age > matureAge
				&& energy > 0.65 * energyMax && infection == 0;
	}

	public void eat(int amount) {
		if (isDead() && !isRemoved()) {
			age -= amount;
		}
	}

	public boolean grab(Entity ent) {

		double distance = distance(ent);
		double minDist = ent.getSize() / 2 + getSize() / 2;

		if (distance > minDist) {
			return false;
		}

		if (ent.getSize() > getSize()) {
			return false;
		}
		D = Math.atan2(-Y + ent.getY(), -X + ent.getX());
		if (ent.attachToTarget(this)) {
			ent.setGrabbed(true);
			grabbing = ent;
			return true;
		}
		return false;
	}

	public boolean drop() {
		if (grabbing == null) {
			return false;
		}

		grabbing.setGrabbed(false);
		grabbing.detach();

		return true;
	}

	/**
	 * draws a text over Entity that will fade out for a given amount of frames
	 *
	 * @param msg
	 *            the message that will be drawn (less than
	 * @param fade
	 *            how long the message will take to fade out
	 */
	public void say(String msg, int fade) {
		if (msg == null) {
			return;
		}
		if (msg.trim().isEmpty()) {
			return;
		}
		if (fade < 0) {
			return;
		}

		message = msg.trim();
		mesage_fade_max = fade;
		message_fade = fade;
	}

	protected String getMessage() {
		return message;
	}

	protected double distance() {
		return distance(tX, tY, tZ);
	}

	protected double distanceTarget(double tx, double ty, double tz) {
		return getWorld().distance(X, Y, Z, tx, ty, tz);
	}

	public void mark() {
		detected = 20;
	}

	@Override
	public void unmark() {
		if (detected > 0) {
			detected--;
		}
	}

	@Override
	public void select() {
		selected = true;
	}

	public final static int STATUS_SLEEP = 0;
	public final static int STATUS_IDLE = 1;
	public final static int STATUS_ALERT = 2;
	public final static int STATUS_THREAT = 3;

	public abstract String getNpcTypeName();

	@Override
	public final String getEntityTypeName() {
		return "NPC." + getNpcTypeName();
	}

}
