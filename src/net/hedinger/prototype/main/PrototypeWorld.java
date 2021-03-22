package net.hedinger.prototype.main;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.Calendar;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

import net.hedinger.prototype.engine.LayerRenderer;
import net.hedinger.prototype.engine.ResourceManager;
import net.hedinger.prototype.engine.StopWatch;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.engine.WorldGenerator;
import net.hedinger.prototype.entities.npcs.Drone;
import net.hedinger.prototype.entities.npcs.DummyChaser;
import net.hedinger.prototype.entities.npcs.DummyRoamer;
import net.hedinger.prototype.entities.npcs.Elite;
import net.hedinger.prototype.entities.npcs.HeadcrabZombie;
import net.hedinger.prototype.entities.npcs.Houndeye;
import net.hedinger.prototype.entities.npcs.Human;
import net.hedinger.prototype.entities.npcs.Sentry;
import net.hedinger.prototype.entities.npcs.Soldier;
import net.hedinger.prototype.entities.npcs.Zombie;

public class PrototypeWorld extends JPanel {
	private static final long serialVersionUID = -1L;
	private JFrame frame;
	private World world;
	private View view;
	private LayerRenderer layerRenderer;
	private int frames = 0;
	private float framerate = 100;
	private static final int FRAME_WIDTH = 1500, FRAME_HEIGHT = 1000;
	private static final int XCORRECTION = 0, YCORRECTION = 0;
	private static final String NAME = "Prototype_World 2.0";
	private static final String CONTACT = "lucas.hedinger@gmail.com";
	final static Color bg = new Color(0, 0, 150);
	final static Color bgL = new Color(250, 250, 250, 50);
	final static Color course = new Color(250, 250, 250, 200);
	final static Color corq = new Color(250, 250, 100, 50);
	final static Color prerq = new Color(250, 250, 250, 50);
	final static Color select = new Color(255, 255, 255, 255);
	final static BasicStroke stroke = new BasicStroke(4.0f);
	final static BasicStroke wideStroke = new BasicStroke(8.0f);
	private int mouseX = 0;
	private int mouseY = 0;
	private int cols = 30, rows = 30, lvls = 3;

	public static StopWatch stopwatch;

	float camDX, camDY, camDZ;

	public static String arglist = "";

	// 0 3ODQ8CRN3GDQC O0AMC7A 7UQT9 5 7 GA97PQ9H07QN5GQGUPQPGH09I768G 1B
	// HB7O0T9OE98CD 9RGNQP0 QN O0 7 G ML 5G HC3NQ6 E H QAUG 8FQ60G
	// M7R32C790IQG9 98RG0PC0APQG99T856LS2 A2P E Q33NLB ABQ2P1 H1IC Q7NBP6 D5A
	// DP6M3FD7OA34PQATPA1C1 OMRM
	public static void main(final String[] args) {

		javax.swing.SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {

				int c = 0, r = 0, l = 0;

				for (String s : args) {
					arglist += " -";
					if (s.startsWith("cols=")) {
						c = Utils.parseInt(s.substring(5), 0);
						arglist += s;
					} else if (s.startsWith("rows=")) {
						r = Utils.parseInt(s.substring(5), 0);
						arglist += s;
					} else if (s.startsWith("lvls=")) {
						l = Utils.parseInt(s.substring(5), 0);
						arglist += s;
					} else {
						arglist += s;
					}
				}

				new PrototypeWorld(c, r, l);
			}
		});
	}

	public PrototypeWorld(int c, int r, int l) {
		if (c > 0) {
			cols = c;
		}
		if (r > 0) {
			rows = r;
		}
		if (l > 0) {
			lvls = l;
		}

		initialize();

		createFrame();
		addMouseMotionListener(new mouseMotionListener());
		addMouseListener(new mouseListener());
		this.addKeyListener(new keyListener());
		this.setFocusable(true);
	}

	private void initialize() {
		initialize(null);
	}

	private void initialize(String hash) {
		ResourceManager.loadResources();

		stopwatch = new StopWatch();

		WorldGenerator generator = new WorldGenerator(cols, rows, lvls);
		generator.run();
		world = generator.getWorld();
		world.alignTiles();
		layerRenderer = new LayerRenderer(world);
		layerRenderer.build(world);
		view = new View(world, layerRenderer);

		frames = 0;

		for (int i = 0; i < world.getLevels(); i++) {
			spawnASet(i);
		}

	}

	private void spawnASet(int level) {

		int ratio = (int) Math.round(0.25f * Math.sqrt(rows * cols));

		spawnEntities(4, 100 * ratio, level); // peeps

		if (level == 0) {
			spawnEntities(8, ratio, level); // zombies
		}
		if (level != 0) {
			spawnEntities(2, 5 * ratio, level); // soldier
			// spawnEntities(1, 7, level); // sentries
			spawnEntities(5, 3, level); // drone
		}

	}

	private void spawnEntities(int type, int num, int level) {
		int c = num;
		int count = 0;
		while (c >= 0) {
			float x = (float) (Math.random() * cols);
			float y = (float) (Math.random() * rows);

			if (world.isOpen(x, y, level)) {
				spawnType(x, y, level, type);
				c--;
			}
			count++;
			if (count > num * 10) {
				return;
			}
		}
	}

	private void createFrame() {
		frame = new JFrame(NAME);

		frame.setContentPane(this);
		frame.setPreferredSize(new Dimension(FRAME_WIDTH, FRAME_HEIGHT));
		frame.setMinimumSize(new Dimension(FRAME_WIDTH, FRAME_HEIGHT));
		frame.setLocation(200, 150);
		frame.setBackground(new Color(0, 0, 37));

		frame.addMouseMotionListener(new mouseMotionListener());
		frame.addMouseListener(new mouseListener());

		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

		frame.pack();

		frame.setVisible(true);
	}

	long gamma = 0;
	long delta = 40;

	long timeprev = Calendar.getInstance().getTimeInMillis();

	long stopwatchReport = 0;

	private Graphics2D g2;

	@Override
	public void paint(Graphics g) {
		g2 = (Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		float diffT = 9999;

		if (frames > 0) {
			long time = Calendar.getInstance().getTimeInMillis();
			diffT = time - timeprev;
			gamma += diffT;
			timeprev = time;
		}

		if (framerate > 200) {
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		Graphics2D graphics = (Graphics2D) g;
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(bg);
		graphics.setStroke(stroke);

		view.think(g, camDX, camDY, camDZ, mouseX, mouseY);
		camDX = 0;
		camDY = 0;
		camDZ = 0;

		view.render(g);
		view.renderFPS(g, Math.round(framerate));
		if (gamma > 30) {
			world.think();

			framerate = frames * 1000 / gamma;
			gamma = 0;
			frames = 0;
		}

		if (stopwatchReport > 60) {
			stopwatch.printReport();
			stopwatchReport = 0;
		}

		stopwatchReport++;

		frames++;
		repaint();
	}

	boolean debug = false;

	int c = 0;

	private void spawnType(float x, float y, float z, int type) {
		if (type == 0) {
			world.spawnEntity(new DummyRoamer(x, y, z));
		}

		if (type == 1) {
			world.spawnEntity(new Sentry(x, y, z));
		}

		if (type == 2) {
			world.spawnEntity(new Soldier(x, y, z));
		}

		if (type == 3) {
			world.spawnEntity(new Elite(x, y, z));
		}

		if (type == 4) {
			world.spawnEntity(new Human(x, y, z));
		}

		if (type == 5) {
			world.spawnEntity(new Drone(x, y, z));
		}

		if (type == 6) {
			world.spawnEntity(new HeadcrabZombie(x, y, z));
		}

		if (type == 7) {
			world.spawnEntity(new Houndeye(x, y, z));
		}

		if (type == 8) {
			world.spawnEntity(new Zombie(x, y, z));
		}

		if (type == 9) {
			world.spawnEntity(new DummyChaser(x, y, z));
		}

	}

	// --------------------------------------------------------------------------
	// ----MOUSELISTENERS
	int entitytype = -1;

	public class mouseListener implements MouseListener {
		@Override
		public void mouseClicked(MouseEvent e) {
			// selected = manager.getCourseName(mouseX, mouseY);
		}

		@Override
		public void mouseEntered(MouseEvent e) {
			mouseX = e.getX() - XCORRECTION;
			mouseY = e.getY() - YCORRECTION;
		}

		@Override
		public void mouseExited(MouseEvent e) {
			mouseX = 0;
			mouseY = 0;
		}

		public double mx = 1, my = 1, mz = 1;

		@Override
		public void mousePressed(MouseEvent e) {

			mouseX = e.getX() - XCORRECTION;
			mouseY = e.getY() - YCORRECTION;

			view.mousePressed();

			if (e.getButton() == MouseEvent.BUTTON1) {

				if (entitytype > -1) {
					spawnType(view.mouseRow, view.mouseCol, view.mouseZ, entitytype);
				}

			}
		}

		@Override
		public void mouseReleased(MouseEvent e) {
			if (dragging) {

			}

			dragging = false;
		}
	}

	// ABCSQ6FBAA D QI E HASAA DM3D HIDAEI A HHF OHH B HT52 I Q12F Q A3 Q DMQ
	// HABQE ALD B3HO4A H BQFQMC QHQ MAAQQ O BPNO
	private boolean dragging = false;
	private int dragX, dragY;

	public class mouseMotionListener implements MouseMotionListener {

		@Override
		public void mouseDragged(MouseEvent e) {
			mouseX = e.getX() - XCORRECTION;
			mouseY = e.getY() - YCORRECTION;

			view.mousePressed();

			if (!dragging) {
				dragX = mouseX;
				dragY = mouseY;
				dragging = true;
			}

		}

		@Override
		public void mouseMoved(MouseEvent e) {
			mouseX = e.getX() - XCORRECTION;
			mouseY = e.getY() - YCORRECTION;

		}
	}

	public class keyListener implements KeyListener {

		@Override
		public void keyPressed(KeyEvent e) {
			if (e.getKeyCode() == KeyEvent.VK_V) {
				view.cycleViewMode();
			}
			if (e.getKeyCode() == KeyEvent.VK_UP || e.getKeyCode() == KeyEvent.VK_W) {
				camDY = -0.15f;
			}
			if (e.getKeyCode() == KeyEvent.VK_DOWN || e.getKeyCode() == KeyEvent.VK_S) {
				camDY = 0.15f;
			}
			if (e.getKeyCode() == KeyEvent.VK_LEFT || e.getKeyCode() == KeyEvent.VK_A) {
				camDX = -0.15f;
			}
			if (e.getKeyCode() == KeyEvent.VK_RIGHT || e.getKeyCode() == KeyEvent.VK_D) {
				camDX = 0.15f;
			}
			if (e.getKeyCode() == KeyEvent.VK_PAGE_UP) {
				camDZ = 1;
			}
			if (e.getKeyCode() == KeyEvent.VK_PAGE_DOWN) {
				camDZ = -1;
			}
			if (e.getKeyCode() == KeyEvent.VK_R) {
				initialize();
			}
			if (e.getKeyCode() == KeyEvent.VK_G) {
				// initialize(JOptionPane.showInputDialog("Enter HashCode:",
				// WorldGenerator.hashCodeString(world)));
			}
			if (e.getKeyCode() == KeyEvent.VK_H) {
				// System.out.println(WorldGenerator.hashCodeString(world));
			}
			if (e.getKeyCode() == KeyEvent.VK_0) {
				entitytype = 0;
			}
			if (e.getKeyCode() == KeyEvent.VK_1) {
				entitytype = 1;
			}
			if (e.getKeyCode() == KeyEvent.VK_2) {
				entitytype = 2;
			}
			if (e.getKeyCode() == KeyEvent.VK_3) {
				entitytype = 3;
			}
			if (e.getKeyCode() == KeyEvent.VK_4) {
				entitytype = 4;
			}
			if (e.getKeyCode() == KeyEvent.VK_5) {
				entitytype = 5;
			}
			if (e.getKeyCode() == KeyEvent.VK_6) {
				entitytype = 6;
			}
			if (e.getKeyCode() == KeyEvent.VK_7) {
				entitytype = 7;
			}
			if (e.getKeyCode() == KeyEvent.VK_8) {
				entitytype = 8;
			}
			if (e.getKeyCode() == KeyEvent.VK_9) {
				entitytype = 9;
			}

		}

		@Override
		public void keyReleased(KeyEvent e) {

		}

		@Override
		public void keyTyped(KeyEvent e) {

		}

	}
}
