package net.hedinger.prototype.entities;

import java.awt.Graphics;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.World;


public abstract class Weapon extends Entity
{
	protected NPC owner;

	protected int clip_size;
	protected int reload_delay;
	protected int fire_delay;
	protected double fire_accuracy; // probability the hit is fatal 0-1
	protected double fire_spread; // max angular spread (radians)
	protected double fire_velocity;
	protected double fire_distance;
	protected double barrel_length;

	private int fire = 999;
	private int clip = 999;
	private int reload = 0;

	protected abstract void fire(World w);

	public Weapon(NPC npc)
	{
		super();
		owner = npc;
	}

	public Weapon(double x, double y, double z)
	{
		super(x, y, z);
	}

	public Weapon(double x, double y, double z, double d)
	{
		super(x, y, z, d);
	}

	@Override
	public void think()
	{
		if (owner == null)
		{
			System.out.println("NULL OWNER");
			return;
		}
		X = owner.getX();
		Y = owner.getY();
		Z = owner.getZ();
		D = owner.getDirection();
	}

	public final boolean use()
	{
		if (owner == null) {
			return false;
		}

		X = owner.getX();
		Y = owner.getY();
		Z = owner.getZ();
		D = owner.getDirection();

		if (clip > clip_size) {
			clip = clip_size;
		}
		if (fire > fire_delay) {
			fire = fire_delay;
		}

		if (clip == 0)
		{
			reload();
			return false;
		}

		if (fire == 0)
		{
			fire = fire_delay;
			clip--;
			fire(owner.getWorld());
		}

		fire--;

		return true;
	}

	public final boolean use(double dist)
	{
		fire_distance = dist;
		return use();
	}

	private void reload()
	{
		reload++;

		if (reload > reload_delay)
		{
			clip = clip_size;
			reload = 0;
		}
	}

	public final double getBarrelLength()
	{
		return barrel_length;
	}

	public final double getBulletVelocity()
	{
		return fire_velocity;
	}

	public final double getBulletAccuracy()
	{
		return fire_accuracy;
	}

	public final double getBulletSpread()
	{
		return fire_spread;
	}

	public int getBulletSpan()
	{
		double temp = fire_distance;

		temp = temp / fire_velocity;

		return round(temp);
	}

	@Override
	protected void draw(Graphics g, View v)
	{

	}

	@Override
	protected void draw_dead(Graphics g, View v)
	{

	}

	public abstract String WeaponType();

	@Override
	public final String getEntityTypeName()
	{
		return "Weapon."+WeaponType();
	}


}
