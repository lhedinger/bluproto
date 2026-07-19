package net.hedinger.prototype.entities;

import net.hedinger.prototype.engine.Utils;

/**
 * A creature's evolvable "mind": a Linear Genetic Programming genome. The
 * heritable part is a <em>variable-length array of instructions</em> over a
 * small register machine; the runtime part is a persistent register bank (so
 * registers carry state between ticks -- <b>memory</b>) and a program counter.
 *
 * <p><b>Continuous, async execution.</b> Each tick the VM runs a fixed budget of
 * instructions and advances the program counter, wrapping at the end -- so a
 * longer program takes proportionally more ticks to complete one pass (a bigger
 * brain thinks slower). {@code WRITE} latches a value into an actuator register;
 * the body reads those latches every tick regardless of where the counter is, so
 * the mind runs at its own cadence and the body samples it. Between writes the
 * last decision persists.
 *
 * <p><b>Everything is a valid program.</b> Operand fields are masked into range
 * at execution, so any random mutation still runs -- there are no illegal
 * programs, which is what keeps the search landscape smooth enough to evolve.
 *
 * <p>Registers persist across ticks but a newborn starts with a blank bank, so
 * two siblings with the same code still diverge once their histories differ.
 */
public final class Brain {

	// ---- instruction set --------------------------------------------------
	// Each instruction is {op, x, y, z}. Operand meaning depends on op; indices
	// are masked into range at execution so no field can be "invalid".
	public static final int NOP = 0, SET = 1, MOV = 2, ADD = 3, SUB = 4, MUL = 5,
			MIN = 6, MAX = 7, NEG = 8, TANH = 9, GT = 10, SKIPZ = 11, SKIPNZ = 12,
			SENSE = 13, WRITE = 14;
	public static final int NUM_OPS = 15;
	private static final String[] OPNAME = { "NOP", "SET", "MOV", "ADD", "SUB", "MUL",
			"MIN", "MAX", "NEG", "TANH", "GT", "SKIPZ", "SKIPNZ", "SENSE", "WRITE" };

	/** General-purpose registers; persist across ticks (this is the memory). */
	public static final int NUM_REG = 12;
	/** Small constant pool the SET opcode draws immediates from. */
	private static final double[] CONST = { -2, -1, -0.5, -0.25, -0.1, 0, 0.1, 0.25, 0.5, 1, 2, 4 };
	/** Registers are clamped to this magnitude so arithmetic can't run away to
	 * NaN/Infinity, but the range is wide enough for counters and timers (memory),
	 * not just the small values reactive logic on normalized sensors produces. */
	private static final double REG_CLAMP = 1.0e4;
	/** Range random operand fields are drawn from (masked per use at exec). */
	private static final int FIELD = 16;

	public static final int MAX_LEN = 64;
	/** Instructions executed per tick unless the caller overrides it; with a
	 * fixed budget, program length sets the length of one thought cycle. */
	public static final int DEFAULT_STEPS_PER_TICK = 1;

	private int[][] code; // heritable: each row is {op, x, y, z}
	private final double[] reg = new double[NUM_REG]; // runtime memory
	private int pc = 0;
	private boolean skipNext = false;

	public Brain(int[][] code) {
		this.code = (code == null || code.length == 0) ? new int[][] { { NOP, 0, 0, 0 } } : code;
	}

	// ---- heredity ---------------------------------------------------------

	/** A random program of the given length (seeded RNG). */
	public static Brain random(int len) {
		len = clampLen(len);
		int[][] c = new int[len][];
		for (int i = 0; i < len; i++) {
			c[i] = randInstr();
		}
		return new Brain(c);
	}

	/** A fresh copy of the code with a blank register bank (newborn memory). */
	public Brain copy() {
		return new Brain(cloneCode(code));
	}

	/**
	 * Variable-length crossover of two parents: a slice of B is spliced into A
	 * (unequal two-point), then the child is mutated. Length drifts naturally, so
	 * brains grow and shrink under selection instead of being fixed.
	 */
	public static Brain child(Brain a, Brain b, double rate) {
		int[][] A = a.code, B = b.code;
		int i1 = randInt(A.length + 1), i2 = randInt(A.length + 1);
		if (i1 > i2) {
			int t = i1;
			i1 = i2;
			i2 = t;
		}
		int j1 = randInt(B.length + 1), j2 = randInt(B.length + 1);
		if (j1 > j2) {
			int t = j1;
			j1 = j2;
			j2 = t;
		}
		int[][] c = new int[(i1) + (j2 - j1) + (A.length - i2)][];
		int k = 0;
		for (int i = 0; i < i1; i++) {
			c[k++] = A[i].clone();
		}
		for (int j = j1; j < j2; j++) {
			c[k++] = B[j].clone();
		}
		for (int i = i2; i < A.length; i++) {
			c[k++] = A[i].clone();
		}
		Brain ch = new Brain(trimLen(c));
		ch.mutate(rate);
		return ch;
	}

	/**
	 * Point mutation (perturb random instruction fields) plus rarer insertion and
	 * deletion of whole instructions, so both the wiring and the length evolve.
	 */
	public void mutate(double rate) {
		for (int[] in : code) {
			if (Utils.random() < rate) {
				int f = randInt(4);
				in[f] = (f == 0) ? randInt(NUM_OPS) : randInt(FIELD);
			}
		}
		if (Utils.random() < rate * 1.5 && code.length < MAX_LEN) {
			code = insert(code, randInt(code.length + 1), randInstr());
		}
		if (Utils.random() < rate * 1.5 && code.length > 1) {
			code = delete(code, randInt(code.length));
		}
	}

	// ---- execution --------------------------------------------------------

	/**
	 * Runs {@code budget} instructions, reading from {@code sensors} on SENSE and
	 * latching into {@code actuators} on WRITE. The program counter, registers and
	 * pending-skip all persist between calls -- one continuous thought stream
	 * sampled a slice at a time.
	 */
	public void step(double[] sensors, double[] actuators, int budget) {
		int n = code.length;
		for (int i = 0; i < budget; i++) {
			int[] in = code[pc];
			pc = (pc + 1) % n;
			if (skipNext) {
				skipNext = false;
				continue;
			}
			exec(in, sensors, actuators);
		}
	}

	private void exec(int[] in, double[] s, double[] act) {
		int op = imod(in[0], NUM_OPS);
		int x = in[1], y = in[2], z = in[3];
		switch (op) {
		case SET:
			set(x, CONST[imod(y, CONST.length)]);
			break;
		case MOV:
			set(x, reg(y));
			break;
		case ADD:
			set(x, reg(y) + reg(z));
			break;
		case SUB:
			set(x, reg(y) - reg(z));
			break;
		case MUL:
			set(x, reg(y) * reg(z));
			break;
		case MIN:
			set(x, Math.min(reg(y), reg(z)));
			break;
		case MAX:
			set(x, Math.max(reg(y), reg(z)));
			break;
		case NEG:
			set(x, -reg(y));
			break;
		case TANH:
			set(x, Math.tanh(reg(y)));
			break;
		case GT:
			set(x, reg(y) > reg(z) ? 1 : 0);
			break;
		case SKIPZ:
			if (reg(x) == 0) {
				skipNext = true;
			}
			break;
		case SKIPNZ:
			if (reg(x) != 0) {
				skipNext = true;
			}
			break;
		case SENSE:
			if (s.length > 0) {
				set(x, s[imod(y, s.length)]);
			}
			break;
		case WRITE:
			if (act.length > 0) {
				act[imod(x, act.length)] = reg(y);
			}
			break;
		default: // NOP
			break;
		}
	}

	private double reg(int i) {
		return reg[imod(i, NUM_REG)];
	}

	private void set(int i, double v) {
		if (Double.isNaN(v)) {
			v = 0;
		}
		reg[imod(i, NUM_REG)] = v < -REG_CLAMP ? -REG_CLAMP : (v > REG_CLAMP ? REG_CLAMP : v);
	}

	// ---- inspection -------------------------------------------------------

	public int length() {
		return code.length;
	}

	public int pc() {
		return pc;
	}

	public double[] registers() {
		return reg;
	}

	/** Human-readable listing of the program, one line per instruction, with the
	 * program counter marked -- for the on-screen inspector. */
	public String[] disassemble(String[] sensorNames, String[] actNames) {
		String[] out = new String[code.length];
		for (int i = 0; i < code.length; i++) {
			int[] in = code[i];
			int op = imod(in[0], NUM_OPS);
			int x = in[1], y = in[2], z = in[3];
			String body;
			switch (op) {
			case SET:
				body = "R" + imod(x, NUM_REG) + " = " + CONST[imod(y, CONST.length)];
				break;
			case MOV:
				body = "R" + imod(x, NUM_REG) + " = R" + imod(y, NUM_REG);
				break;
			case ADD:
			case SUB:
			case MUL:
			case MIN:
			case MAX: {
				String o = op == ADD ? "+" : op == SUB ? "-" : op == MUL ? "*"
						: op == MIN ? "min" : "max";
				body = "R" + imod(x, NUM_REG) + " = R" + imod(y, NUM_REG) + " " + o + " R" + imod(z, NUM_REG);
				break;
			}
			case NEG:
				body = "R" + imod(x, NUM_REG) + " = -R" + imod(y, NUM_REG);
				break;
			case TANH:
				body = "R" + imod(x, NUM_REG) + " = tanh R" + imod(y, NUM_REG);
				break;
			case GT:
				body = "R" + imod(x, NUM_REG) + " = R" + imod(y, NUM_REG) + " > R" + imod(z, NUM_REG);
				break;
			case SKIPZ:
				body = "skip next if R" + imod(x, NUM_REG) + " == 0";
				break;
			case SKIPNZ:
				body = "skip next if R" + imod(x, NUM_REG) + " != 0";
				break;
			case SENSE:
				body = "R" + imod(x, NUM_REG) + " = sense " + name(sensorNames, y);
				break;
			case WRITE:
				body = "act " + name(actNames, x) + " = R" + imod(y, NUM_REG);
				break;
			default:
				body = "nop";
				break;
			}
			out[i] = (i == pc ? "> " : "  ") + String.format("%2d ", i) + body;
		}
		return out;
	}

	private static String name(String[] names, int idx) {
		if (names == null || names.length == 0) {
			return "#" + idx;
		}
		return names[imod(idx, names.length)];
	}

	// ---- small helpers ----------------------------------------------------

	private static int[] randInstr() {
		return new int[] { randInt(NUM_OPS), randInt(FIELD), randInt(FIELD), randInt(FIELD) };
	}

	private static int randInt(int n) {
		return n <= 0 ? 0 : (int) (Utils.random() * n);
	}

	/** Non-negative modulo, so any int operand maps to a valid index. */
	private static int imod(int a, int m) {
		int r = a % m;
		return r < 0 ? r + m : r;
	}

	private static int clampLen(int len) {
		return len < 1 ? 1 : (len > MAX_LEN ? MAX_LEN : len);
	}

	private static int[][] trimLen(int[][] c) {
		if (c.length == 0) {
			return new int[][] { { NOP, 0, 0, 0 } };
		}
		if (c.length <= MAX_LEN) {
			return c;
		}
		int[][] t = new int[MAX_LEN][];
		System.arraycopy(c, 0, t, 0, MAX_LEN);
		return t;
	}

	private static int[][] cloneCode(int[][] c) {
		int[][] d = new int[c.length][];
		for (int i = 0; i < c.length; i++) {
			d[i] = c[i].clone();
		}
		return d;
	}

	private static int[][] insert(int[][] c, int at, int[] instr) {
		int[][] d = new int[c.length + 1][];
		System.arraycopy(c, 0, d, 0, at);
		d[at] = instr;
		System.arraycopy(c, at, d, at + 1, c.length - at);
		return d;
	}

	private static int[][] delete(int[][] c, int at) {
		int[][] d = new int[c.length - 1][];
		System.arraycopy(c, 0, d, 0, at);
		System.arraycopy(c, at + 1, d, at, c.length - at - 1);
		return d;
	}
}
