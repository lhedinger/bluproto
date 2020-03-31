package net.hedinger.prototype.entities.weapons;

import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.*;

public class Gattlingun extends Weapon
{
	private void init()
	{
		clip_size = 40;
		reload_delay = 5;
		fire_delay = 1;
		fire_accuracy = 0.02;
		fire_spread = 0.05;
		fire_velocity = 0.3;
		fire_distance = 20;
		barrel_length = 0.1;
	}
	
	
	public Gattlingun(NPC npc)
	{
		super(npc);
		init();
	}

	public Gattlingun(double x, double y, double z)
	{
		super(x, y, z);
		init();
	}

	public Gattlingun(double x, double y, double z, double d)
	{
		super(x, y, z, d);
		init();
	}

	public void fire(World w)
	{
		w.spawnEntity(new Bullet(this));
	}
	
	public String WeaponType()
	{
		return "Machinegun";
	}

}
