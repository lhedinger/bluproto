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

	/** Heritable trait vector; null for species that do not use one (yet). */
	protected Genome genome = null;

	public Genome getGenome() {
		return genome;
	}

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

	protected TreeMap<Double, NPC> targets = new TreeMap<Double, NPC>();
	protected TreeMap<Double, NPC> focusTargets = new TreeMap<Double, NPC>();

	// Topological neighbourhood: like real flocks (starlings track ~7 nearest
	// neighbours regardless of crowding), each NPC only tracks its nearest
	// MAX_NEIGHBORS. This bounds the per-tick neighbour loops at O(K) instead of
	// O(local density), so a dense pile-up costs the same per entity as a light
	// crowd.
	protected int MAX_NEIGHBORS = Integer.getInteger("blu.k", 7);

	// Staggered-update period multiplier (1 = each NPC re-scans every
	// SEARCH_FREQ ticks). Tunable via -Dblu.stagger=N for benchmarking the
	// freshness/speed trade-off.
	public static int STAGGER = Integer.getInteger("blu.stagger", 1);

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

		blink_random = (int) (Utils.random() * 0);
	}

	@Override
	protected void run_extended() {
		PrototypeWorld.stopwatch.start();
		targets = scanTargets(targets);
		PrototypeWorld.stopwatch.stop();

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
			if (canTouch(npc)) {
				// The old code went angle = atan2(-dy,-dx) and then
				// cos(angle)*hypot / sin(angle)*hypot -- a transcendental
				// round-trip that exactly reconstructs (-dx, -dy). Push
				// directly away from the neighbour instead.
				dX += -dx * spring;
				dY += -dy * spring;
			}
		}
	}

	// |///////////////////////////////
	// |///////////////////////////////////////////////////////////////
	// LOS METHODS
	// |///////////////////////////////////////////////////////////////
	// |///////////////////////////////

	protected boolean canTouch(Entity e) {
		// Squared-distance compare: equivalent to distance(e) < minDist for
		// non-negative values, without the per-neighbour sqrt.
		double ddx = e.getX() - X;
		double ddy = e.getY() - Y;
		double ddz = e.getZ() - Z;
		double minDist = e.getSize() / 2 + getSize() / 2;
		return ddx * ddx + ddy * ddy + ddz * ddz < minDist * minDist;
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
			double d = 0.5 + Utils.random() * 0.7;
			double a = variation(D, Math.PI * 0.5);
			if (Utils.random() * 4 < 1) {
				a = Utils.random() * 2 * Math.PI;
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
			double d = 0.5 + Utils.random() * 3;
			double a = variation(direction, Math.PI * 0.25);
			if (Utils.random() * 10 < 1) {
				a = Utils.random() * 2 * Math.PI;
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

		double angle = Math.atan2(Y - tY, X - tX) + Math.PI;

		if (angle > 2 * Math.PI) {
			angle -= 2 * Math.PI;
		}
		if (angle < 0) {
			angle += 2 * Math.PI;
		}

		double dA = angle - D + Math.PI;

		if (dA > Math.PI) {
			dA = -2 * Math.PI + dA;
		}
		if (dA < -Math.PI) {
			dA = 2 * Math.PI + dA;
		}

		double dir = D;

		if (dA > 0) {
			dir += (Math.sqrt(Math.abs(dA)) / turn);
		} else if (dA < 0) {
			dir -= (Math.sqrt(Math.abs(dA)) / turn);
		}

		roam(speed, turn, dir);

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
			D = Utils.random() * Math.PI * 2;
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

	/** Keeps only the nearest k entries of a distance-sorted target map. */
	private TreeMap<Double, NPC> capNearest(TreeMap<Double, NPC> in, int k) {
		if (in == null || in.size() <= k) {
			return in;
		}
		TreeMap<Double, NPC> out = new TreeMap<Double, NPC>();
		for (Double key : in.navigableKeySet()) {
			out.put(key, in.get(key));
			if (out.size() >= k) {
				break;
			}
		}
		return out;
	}

	private TreeMap<Double, NPC> scanTargets(TreeMap<Double, NPC> ts) {
		// Staggered update: instead of each NPC re-scanning its neighbourhood at
		// a random ~1/SEARCH_FREQ chance (which clumps -- many can fire on the
		// same tick), give every NPC a fixed phase from its ID so exactly
		// 1/period of the population does the expensive full scan each tick. Same
		// average refresh rate, evenly spread across ticks. STAGGER lengthens the
		// period to trade perception freshness for speed.
		int period = Math.max(1, SEARCH_FREQ * STAGGER);
		if (((getID() + age) % period) == 0) {
			// Bounded nearest-K gather: cost is O(K), not O(local density).
			return getWorld().searchNearestNPC(X, Y, Z, D, LOS_RANGE, LOS_FOV, getID(), MAX_NEIGHBORS);
		}

		// Revalidate the cached list in place -- no defensive copy needed, the
		// output map is separate and nothing here mutates the source.
		TreeMap<Double, NPC> output = new TreeMap<Double, NPC>();
		if (ts != null) {
			for (NPC e : ts.values()) {
				if (seeTarget(e, LOS_RANGE, LOS_FOV, "", false)) {
					if (isFriendly() && e.isHostile()) {
						if (!isDead() && !e.isDead()) {
							e.mark();
						}
					}
					output.put(distance(e.getX(), e.getY(), e.getZ()), e);
				}
			}
		}

		return capNearest(output, MAX_NEIGHBORS);
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

		// Filters the current perception list (the targets field -- note the ts
		// parameter was historically ignored here) into a fresh map. Iterate the
		// source directly; the old defensive temp copy doubled the boxed-key
		// TreeMap allocations of every think() tick.
		TreeMap<Double, NPC> output = new TreeMap<Double, NPC>();

		for (NPC e : targets.values()) {
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
		if (!World.filterType(t.getEntityTypeName(), types, include)) {
			return false;
		}

		return true;
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

	public boolean canMate() {
		return false;
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
		grabbing = null;

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
