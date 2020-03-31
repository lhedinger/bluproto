package net.hedinger.prototype.entities;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.View;

public class Door extends Entity
{
	private int status = DOOR_CLOSED;
	private static final int open_delay = -40;
	private static final int close_delay = 40;
	private int delay_counter = 0;
	private static BufferedImage open_ud, closed_ud, offline_ud;
	private static BufferedImage open_lr, closed_lr, offline_lr;
	private boolean triggered = false;

	private static boolean initialized = false;

	private static void initializeImages()
	{
		initialized = true;
		try
		{
			open_ud = ImageIO.read(new File("res/doors/door_ud_open.png"));
			closed_ud = ImageIO.read(new File("res/doors/door_ud_closed.png"));
			offline_ud = ImageIO.read(new File("res/doors/door_ud_offline.png"));
		}
		catch (Exception e)
		{
			System.out.println(e.toString());
		}

		try
		{
			open_lr = ImageIO.read(new File("res/doors/door_lr_open.png"));
			closed_lr = ImageIO.read(new File("res/doors/door_lr_closed.png"));
			offline_lr = ImageIO.read(new File("res/doors/door_lr_offline.png"));
		}
		catch (Exception e)
		{
			System.out.println(e.toString());
		}

	}

	public Door(double x, double y, double z, int d)
	{
		super((int) x, (int) y, (int) z, d % 2);

		this.size_diameter = 64;

		if (!initialized) {
			initializeImages();
		}
	}

	@Override
	protected void think()
	{
		int r = (int) (this.D % (Math.PI / 2));

		if (Math.random() * 600 < 1) {
			toggle();
		}

		if (delay_counter == 0)
		{
			if (isTriggered())
			{
				if (status == DOOR_CLOSED) {
					delay_counter = open_delay;
				} else if (status == DOOR_OPEN) {
					delay_counter = close_delay;
				}
			}
		}
		else
		{
			status = DOOR_MOVING;

			if (delay_counter < 0)
			{
				// opening
				delay_counter++;

				if (delay_counter == 0)
				{
					status = DOOR_OPEN;
					if (r == 0)
					{
						getWorld().getTile(X, Y + 1, Z).openDoor(1);
						getWorld().getTile(X, Y, Z).openDoor(3);
					}
					else
					{
						getWorld().getTile(X, Y, Z).openDoor(2);
						getWorld().getTile(X + 1, Y, Z).openDoor(4);
					}
				}

			}
			else if (delay_counter > 0)
			{
				// closing
				delay_counter--;

				if (delay_counter == 0)
				{
					status = DOOR_CLOSED;

				}

			}

		}

		if (status == DOOR_CLOSED)
		{
			if (r == 0)
			{
				getWorld().getTile(X, Y, Z).closeDoor(0);
				getWorld().getTile(X, Y - 1, Z).closeDoor(2);
			}
			else
			{
				getWorld().getTile(X - 1, Y, Z).closeDoor(1);
				getWorld().getTile(X, Y, Z).closeDoor(3);
			}
		}
		if (status == DOOR_OPEN)
		{
			if (r == 0)
			{
				getWorld().getTile(X, Y, Z).openDoor(0);
				getWorld().getTile(X, Y - 1, Z).openDoor(2);
			}
			else
			{
				getWorld().getTile(X - 1, Y, Z).openDoor(1);
				getWorld().getTile(X, Y, Z).openDoor(3);
			}
		}

	}

	@Override
	protected void draw(Graphics g, View v)
	{
		Graphics2D g2 = (Graphics2D) g;
		int r = (int) (this.D % (Math.PI / 2));

		if (status == DOOR_OPEN)
		{
			if (r == 0) // vertical
			{
				g2.drawImage(open_ud, pixelX(v, 32), pixelY(v, 32), null);
			}
			else
			{
				g2.drawImage(open_lr, pixelX(v, 32), pixelY(v, 32), null);
			}
		}
		if (status == DOOR_MOVING)
		{
			if (age % 10 < 5)
			{
				if (r == 0) // vertical
				{
					g2.drawImage(open_ud, pixelX(v, 32), pixelY(v, 32), null);
				}
				else
				{
					g2.drawImage(open_lr, pixelX(v, 32), pixelY(v, 32), null);
				}
			}
			else
			{
				if (r == 0) // vertical
				{
					g2.drawImage(closed_ud, pixelX(v, 32), pixelY(v, 32), null);
				}
				else
				{
					g2.drawImage(closed_lr, pixelX(v, 32), pixelY(v, 32), null);
				}
			}
		}
		if (status == DOOR_CLOSED)
		{
			if (r == 0) // vertical
			{
				g2.drawImage(closed_ud, pixelX(v, 32), pixelY(v, 32), null);
			}
			else
			{
				g2.drawImage(closed_lr, pixelX(v, 32), pixelY(v, 32), null);
			}
		}
	}

	private boolean isTriggered()
	{
		if (triggered)
		{
			triggered = false;
			return true;
		}
		return false;
	}

	private void toggle()
	{
		triggered = true;
	}

	@Override
	public String EntityType()
	{
		return "Door";
	}

	private static final int DOOR_OFFLINE = -1;
	private static final int DOOR_OPEN = 0;
	private static final int DOOR_MOVING = 1;
	private static final int DOOR_CLOSED = 2;

}
