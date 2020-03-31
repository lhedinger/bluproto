package net.hedinger.prototype.entities.weapons;

import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.*;

//multiple grenade launcher
public class MGL extends Weapon
{
	private void init()
	{
		clip_size = 6;
		reload_delay = 200;
		fire_delay = 100;
		fire_accuracy = 0.01;
		fire_spread = 0.001;
		fire_velocity = 0.1;
		fire_distance = 10;
		barrel_length = 0.06;
	}
	
	
	public MGL(NPC npc)
	{
		super(npc);
		init();
	}

	public MGL(double x, double y, double z)
	{
		super(x, y, z);
		init();
	}

	public MGL(double x, double y, double z, double d)
	{
		super(x, y, z, d);
		init();
	}

	public void fire(World w)
	{
		w.spawnEntity(new Grenade(this));
	}
	
	public String WeaponType()
	{
		return "MGL";
	}

}
