
package net.hedinger.prototype.engine;

import java.awt.Point;
import java.util.HashSet;

public class Sector
{
    private World world;
    private String name;
    private HashSet<Integer> tiles;
    private Point center;
    private int origin;
    private int level;

    public static final char[] alphabet = { 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K',
            'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z' };

    public Sector(World world, int origin, int level)
    {
        this.world = world;

        float x, y;

        x = world.getColums();
        y = world.getRows();

        x = world.hashCol(origin) / x;
        y = world.hashRow(origin) / y;

        x = x * alphabet.length;
        y = y * alphabet.length;

        this.name = "Sector" + alphabet[(int) x] + "" + (int) y;
        this.origin = origin;
        this.level = level;

        tiles = new HashSet<Integer>();
        tiles.add(origin);
    }

    public Sector(World world, String name, int origin, int level)
    {
        this.world = world;
        this.name = name;
        this.origin = origin;
        this.level = level;

        tiles = new HashSet<Integer>();
        tiles.add(origin);
    }

    public String getName()
    {
        return name;
    }

    public void add(int location)
    {
        tiles.add(location);
        center = null;
    }

    public int size()
    {
        if (tiles == null) {
            return 0;
        }
        return tiles.size();
    }

    public HashSet<Integer> getTiles()
    {
        return tiles;
    }

    public int getOrigin()
    {
        return origin;
    }

    public Point getCenter()
    {
        if (center != null) {
            return center;
        }

        int lx = -1, rx = -1;
        int ly = -1, ry = -1;

        int x, y;
        // System.out.println(name + ":");
        // System.out.println("size = " + tiles.size());
        for (Integer i : tiles)
        {
            x = world.hashCol(i);
            y = world.hashRow(i);

            if (lx == -1 || lx > x) {
                lx = x;
            }
            if (rx == -1 || rx < x) {
                rx = x;
            }
            if (ly == -1 || ly > y) {
                ly = y;
            }
            if (ry == -1 || ry < y) {
                ry = y;
            }

        }

        // System.out.println(lx + " " + rx);
        // System.out.println(ly + " " + ry);

        double mx, my;

        mx = rx - lx;
        mx = lx + mx / 2;
        my = ry - ly;
        my = ly + my / 2;

        center = new Point();
        center.setLocation(mx, my);

        mx = world.getColums();
        my = world.getRows();

        mx = center.getX() / mx;
        my = center.getY() / my;

        mx = mx * alphabet.length;
        my = my * alphabet.length;

        name = "Sector" + alphabet[(int) mx] + "" + (int) my;
        return center;
    }

}
