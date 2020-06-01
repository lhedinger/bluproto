package net.hedinger.prototype.entities.npcs;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.entities.*;
import net.hedinger.prototype.entities.weapons.*;

import java.awt.Color;
import java.util.TreeMap;

public class Soldier extends NPC
{
	private static final double SOLDIER_RANGE = 2.5; // zombie los range (pixels)
	private static final double SOLDIER_FOV = Math.PI * 0.25;
	private static final double SOLDIER_SPEED = 0.05; // max speed
	private static final int SOLDIER_TURN = 10; // max turn speed
	private static final int SOLDIER_SF = 25;
	private Weapon weapon;
	boolean selected = false;

	private String[] SOLDIER_IGNORE = { "Entity.NPC.Drone", "Entity.NPC.Soldier",
			"Entity.NPC.Sentry", "Entity.NPC.Human", "Entity.NPC.Spore" };

	private TreeMap<Double, NPC> enemies = null;
	private TreeMap<Double, NPC> squad = null;

	private NPC enemy = null;

	public Soldier(double x, double y, double z)
	{
		super(x, y, z);
		hostile = 0;
		size = 6;
		health = 100;
		SEARCH_FREQ = SOLDIER_SF;
		LOS_RANGE = SOLDIER_RANGE;
		LOS_FOV = SOLDIER_FOV;
		drawLOS = true;
		int n = (int) (Math.random() * 3);
		switch (n)
		{
		case (0):
			weapon = new Shotgun(this);
			break;
		case (1):
			weapon = new Rifle(this);
			break;
		case (2):
			weapon = new Machinegun(this);
			break;
		case (3):
			weapon = new MGL(this);
			break;
		default:
			weapon = new Rifle(this);
			break;

		}

	}

	protected void think()
	{
		weapon.think();

		enemies = getTargets(enemies, SOLDIER_IGNORE, false);

		if (selected)
			say("yes?", 1);

		if (squad == null || squad.size() < 3)
			squad = getTargets(0, squad, 3, Math.PI, "Entity.NPC.Soldier", true);

		if (!enemies.isEmpty())
		{
			say("contact!", 200);
			enemy = enemies.firstEntry().getValue();

			status = NPC.STATUS_THREAT;
		}
		else
		{
			if (pathFinding)
			{
				say("moving!", 20);
				if (!followPath(SOLDIER_SPEED, SOLDIER_TURN))
					pathFinding = false;
			}
			status = NPC.STATUS_ALERT;
		}

		if (seeTarget(enemy, SOLDIER_IGNORE, false))
		{
			lockTarget(enemy);

			if (distance() > 2.0)
			{
				say("moving in!", 100);
				chase(SOLDIER_SPEED, SOLDIER_TURN);
			}
			else
				chase(0, SOLDIER_TURN);

			if (isInLOS(SOLDIER_RANGE, Math.PI * 0.2))
			{
				say("engaging!", 50);

				if (distance() < 3.0)
				{
					if (weapon.use(distance()))
						move(0);
					if (distance() < 1.0)
						backup(SOLDIER_SPEED * 0.75);
				}
			}
		}
		else
		{
			if (squad.size() < 3 || squad.size() > 6)
				roam(SOLDIER_SPEED * 0.5, SOLDIER_TURN);
			else
			{
				Entity sq = getClosestNPC(squad);
				if (((NPC) sq).getStatus() == NPC.STATUS_THREAT)
				{
					if (!follow(SOLDIER_SPEED * 0.8, SOLDIER_TURN, sq, 1))
						roam(SOLDIER_SPEED * 0.8, SOLDIER_TURN);
				}
				else
				{
					if (!follow(SOLDIER_SPEED * 0.5, SOLDIER_TURN, sq, 3))
						roam(SOLDIER_SPEED * 0.5, SOLDIER_TURN);
				}
			}

		}
	}

	public void select()
	{
		selected = true;
	}

	public void unselect()
	{
		selected = false;
	}

	private boolean pathFinding = false;

	public void gotoDest(double x, double y)
	{
		if (!selected)
			return;

		if (!getWorld().isValid(x, y, Z))
			return;

		this.generatePath(x, y, Z);
		pathFinding = true;
	}

	public String getNpcTypeName()
	{
		return "Soldier";
	}
}
