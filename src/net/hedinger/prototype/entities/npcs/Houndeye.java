package net.hedinger.prototype.entities.npcs;

import java.awt.*;
import java.util.TreeMap;

import net.hedinger.prototype.entities.NPC;


public class Houndeye extends NPC
{
	private static final double HOUNDEYE_RANGE = 10; // HOUNDEYE los range
	// (pixels)
	private static final double HOUNDEYE_FOV = Math.PI * 0.5;
	private static final double HOUNDEYE_SPEED = 0.015; // max speed
	private static final int HOUNDEYE_TURN = 10; // max turn speed
	private static final int HOUNDEYE_SF = 50;
	private int SF = 0;
	private int HOUNDEYE_MF = 16;
	private int MF = 0;

	private final String[] HOUNDEYE_FOOD = { "Entity.NPC.Spore" };
	private final String[] HOUNDEYE_ENEMIES = { "Entity.NPC.Sentry", "Entity.NPC.Soldier",
			"Entity.NPC.Human", "Entity.NPC.Zombie", "Entity.NPC.Bullsquid" };

	private TreeMap<Double, NPC> foods = null;
	private TreeMap<Double, NPC> enemies = null;
	private TreeMap<Double, NPC> mates = null;

	private NPC food = null;
	private NPC enemy = null;
	private NPC mate = null;

	public Houndeye(double x, double y, double z)
	{
		super(x, y, z);
		col = new Color(0, 180, 0);
		size = 4;
		health = 100;
		SEARCH_FREQ = HOUNDEYE_SF;
		LOS_RANGE = HOUNDEYE_RANGE;
		LOS_FOV = HOUNDEYE_FOV;
	}

	public Houndeye(double x, double y, double z, double d)
	{
		super(x, y, z, d);
		col = new Color(0, 180, 0);
		size = 4;
		health = 100;
		SEARCH_FREQ = HOUNDEYE_SF;
		LOS_RANGE = HOUNDEYE_RANGE;
		LOS_FOV = HOUNDEYE_FOV;
	}

	public void think()
	{
		enemies = getTargets(enemies, HOUNDEYE_ENEMIES, true);
		mates = getTargets(mates, HOUNDEYE_ENEMIES, true);
		foods = getTargets(foods, HOUNDEYE_ENEMIES, true);

		hunger -= metabolism;

		if (hunger <= 0)
			kill();
		if (age > 3000)
			kill();
		if (age > 100)
			size = 3;
		if (age > 200)
			size = 4;
		if (age > 400)
			size = 5;
		if (age > 800)
			size = 6;

		if (hunger < 25)
		{
			if (!foods.isEmpty())
			{
				food = getClosestNPC(foods, 500, true);
				if (food != null)
				{
					lockTarget(food);

					if (distance() < 0.1)
					{
						hunger = 100;
						food.eat(100);
						food = null;
					}
				}
			}
			chase(HOUNDEYE_SPEED, 2);
			return;
		}
		else
			roam(HOUNDEYE_SPEED * 0.7, HOUNDEYE_TURN);

	}

	public boolean canMate()
	{
		if (pregnant)
			return false;
		if (!wantsToMate)
			return false;

		return true;
	}

	private boolean wantsToMate = false;
	private boolean pregnant = false;
	private int pregnant_counter = 0;
	private double hunger = 25;
	private double metabolism = 0.02;
	private int matingcooldown = 700;

	public String getNpcTypeName()
	{
		return "Houndeye";
	}
}