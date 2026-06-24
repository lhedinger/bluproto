package net.hedinger.prototype.entities.npcs;

import net.hedinger.prototype.engine.Utils;

import net.hedinger.prototype.entities.NPC;

import java.awt.Color;
import java.util.TreeMap;

public class Spore extends NPC
{

	private final double SPORE_DENSITY = variation(0.5, 0.25);
	private final double SPORE_SPEED = 0.05;
	private final int SPORE_MAXAGE = (int) Math.round(variation(1000, 100));
	private final int SPORE_AIRTIME = (int) Math.round(variation(10, 5));
	private int airtime = 0;
	private double spore_size = 2.0f;
	private boolean podmode = false;

	private TreeMap<Double, NPC> spores = null;

	public Spore(double x, double y, double z)
	{
		super(x, y, z);
		col = new Color(100, 0, 0, 200);
		size = 1;
		drawLine = false;
		age = SPORE_MAXAGE / 2;
		SEARCH_FREQ = 0;
	}

	public Spore(double x, double y, double z, double a)
	{
		super(x, y, z);
		col = new Color(100, 0, 0, 200);
		size = 1;
		podmode = true;
		drawLine = false;
		SEARCH_FREQ = 0;
		LOS_RANGE = SPORE_DENSITY;
		LOS_FOV = Math.PI;
	}

	protected void think()
	{
		if (podmode)
		{
			age = 0;
			if (!isColliding())
			{
				move(SPORE_SPEED);
				airtime++;
			}
			if (airtime > SPORE_AIRTIME)
			{
				podmode = false;

				spores = getTargets(spores, "Entity.NPC.Spore", true);

				if (!spores.isEmpty())
				{
					kill();
					return;
				}
			}
			return;
		}

		tX = X;
		tY = Y;
		tZ = Z;

		move(0);

		if (spore_size <= 0)
		{
			this.kill();
			return;
		}

		double temp = age;
		temp = temp / SPORE_MAXAGE;

		spore_size = 6 * Math.sqrt(temp);

		size = (int) Math.round(spore_size);

		if (age > SPORE_MAXAGE)
			explode();

	}

	public void eat(double amount)
	{
		spore_size -= amount;
	}

	private void explode()
	{
		int n = 4;

		/*
		 * if(Utils.random()1.5 < 1) { n = 3; if(Utils.random()2 < 1) { n = 4;
		 * if(Utils.random()2 < 1) { n = 5; } } }
		 */

		for (int i = 0; i < n; i++)
		{
			getWorld().spawnEntity(new Spore(X, Y, Z, Utils.random() * 2 * Math.PI));
		}

		this.kill();
	}

	public String getNpcTypeName()
	{
		return "Spore";
	}
}
