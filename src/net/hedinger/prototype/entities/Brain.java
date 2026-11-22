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
			SENSE = 13, WRITE = 14,
			// Comparing skips, appended so every opcode a living genome already
			// carries keeps its meaning (an opcode is taken modulo NUM_OPS).
			SKIPGT = 15, SKIPLT = 16,
			// An intent in one line, appended for the same reason.
			SEEKWATER = 17;
	public static final int NUM_OPS = 18;
	/** Opcode mnemonics, indexed by opcode. */
	public static final String[] OP_NAMES = { "NOP", "SET", "MOV", "ADD", "SUB", "MUL",
			"MIN", "MAX", "NEG", "TANH", "GT", "SKIPZ", "SKIPNZ", "SENSE", "WRITE",
			"SKIPGT", "SKIPLT", "SEEKWATER" };

	/** Whether {@code op} is one of the skips: it gates the line after it. */
	public static boolean isSkip(int op) {
		int o = imod(op, NUM_OPS);
		return o == SKIPZ || o == SKIPNZ || o == SKIPGT || o == SKIPLT;
	}

	/** General-purpose registers; persist across ticks (this is the memory). */
	public static final int NUM_REG = 12;
	/**
	 * The carry: every instruction that produces a value also leaves it in
	 * R0, so reading R0 reads "what the line before produced". SET, MOV, the
	 * arithmetic, GT and SENSE carry their result; WRITE carries the value it
	 * wrote. A skip, a NOP and a skipped line produce nothing and leave the
	 * carry as it was, so junk between two lines does not break the chain.
	 *
	 * <p>It entangles the program. One source operand in twelve already names
	 * R0, so a mutation that lands there wires a line to whatever ran before
	 * it, instead of to a register some unrelated line wrote long ago; chains
	 * form without two lines having to agree on a register, the way a stack
	 * passes values in Push. Writing to R0 as a destination means "pass it on,
	 * keep nothing".
	 */
	public static final int CARRY = 0;
	/** Small constant pool the SET opcode draws immediates from. */
	public static final double[] CONST = { -2, -1, -0.5, -0.25, -0.1, 0, 0.1, 0.25, 0.5, 1, 2, 4 };
	/** Registers are clamped to this magnitude so arithmetic can't run away to
	 * NaN/Infinity, but the range is wide enough for counters and timers (memory),
	 * not just the small values reactive logic on normalized sensors produces. */
	private static final double REG_CLAMP = 1.0e4;
	/**
	 * Range random operand fields are drawn from (masked per use at exec).
	 *
	 * <p><b>This must be at least as large as the widest thing an operand can
	 * name.</b> Operands are masked into range at execution, so a value below the
	 * sensor count does not fail — it silently makes the channels above it
	 * unreachable, because no mutation can ever produce an index that high. At 16,
	 * with 34 sensors, eighteen of them were invisible to every evolved mind: the
	 * whole forage, waypoint, thirst and intent suite existed, was filled every
	 * tick, and could not be read. Nothing failed; the sim simply evolved without
	 * them.
	 *
	 * <p>{@code EverySensorIsReachable} pins this against
	 * {@link AgentIO#NUM_SENSORS} so adding a channel cannot quietly reintroduce
	 * the same silence. Kept a multiple of {@link #NUM_REG} and of the constant
	 * pool (both 12) so register and immediate operands stay uniformly
	 * distributed, and with headroom above the sensor count so the next channel
	 * added does not have to touch this. Raised from 48 to 60 when the three
	 * climate senses took the count to 51.
	 */
	public static final int FIELD = 60;

	public static final int MAX_LEN = 64;
	/**
	 * How close to zero a register has to be for {@code SKIPZ} to call it zero
	 * (and {@code SKIPNZ} to call it not). The test was exact, and a mind's
	 * registers mostly hold sensor readings and arithmetic on them: a threat at
	 * proximity 0.001 was "something" and a bearing of 1e-9 was "a direction",
	 * so a decision hung on noise, and a line that should have gated on a
	 * reading gated on whether the reading was exactly nothing. Within the
	 * band, a skip treats a value as nothing. The band sits under the smallest
	 * step in the constant pool (0.1), so a SET still reads as clearly zero or
	 * clearly not, and a GT's 0 and 1 are untouched.
	 */
	public static final double ZERO_BAND = 0.05;
	/**
	 * How short of its threshold hydration may be and still count as
	 * complete for {@code SEEKWATER}: the body's own {@link NPC#DRINK_ONSET},
	 * the thirst it starts drinking at. A body less thirsty than that would
	 * not drink at the water if it went, so no threshold sends it there. On
	 * the live world 19% of padded hunter founders, slaked, still sought
	 * water through a random SEEKWATER line whose threshold sat at 1.
	 */
	public static double waterTolerance() {
		return NPC.DRINK_ONSET; // read live: the onset is a tuning, not a constant
	}
	/** Instructions executed per tick unless the caller overrides it; with a
	 * fixed budget, program length sets the length of one thought cycle. Two
	 * lines a tick: a 22-line forager goes round in 11 ticks instead of 22, so
	 * a line inserted by mutation costs half a tick of reaction instead of a
	 * whole one, while length is still time -- a long program is still a
	 * slower thought than a short one. */
	public static final int DEFAULT_STEPS_PER_TICK = 2;

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
	 * Crossover of two parents, HOMOLOGOUS: two cut points are drawn once and
	 * applied at the same positions in both programs, so the child is A's head,
	 * B's middle and A's tail with every instruction keeping the place it had.
	 * Two copies of one program make that program, every time; the child of two
	 * different programs mixes them position for position. Length still drifts
	 * -- from the insertion and deletion in {@link #mutate}, and from parents of
	 * different lengths, since the cuts range over the longer of the two and
	 * each parent contributes what it has at those positions.
	 *
	 * <p>It used to be unequal: A's cut points and B's were drawn independently,
	 * so a random slice of B replaced a random and differently-placed slice of A.
	 * For a program whose instructions depend on each other's registers that is
	 * not a mix but a scramble. Measured on the starter forager: two IDENTICAL
	 * copies of it, crossed with no mutation at all, produced a child that still
	 * sought forage and moved 53 per cent of the time, against 76 per cent for a
	 * budded copy mutated at the founders' rate. Sex was a coin flip on whether
	 * the child could eat, which is why the budding parasites ran to two hundred
	 * generations while no sexual grazer lineage got past its first: a calf was
	 * born with a five-instruction program that wrote nothing, and sat on bare
	 * ground beside its herd until it starved.
	 */
	public static Brain child(Brain a, Brain b, double rate) {
		int[][] A = a.code, B = b.code;
		int span = Math.max(A.length, B.length);
		int i1 = randInt(span + 1), i2 = randInt(span + 1);
		if (i1 > i2) {
			int t = i1;
			i1 = i2;
			i2 = t;
		}
		int head = Math.min(i1, A.length);
		int mid1 = Math.min(i1, B.length), mid2 = Math.min(i2, B.length);
		int tail = Math.min(i2, A.length);
		int[][] c = new int[head + (mid2 - mid1) + (A.length - tail)][];
		int k = 0;
		for (int i = 0; i < head; i++) {
			c[k++] = A[i].clone();
		}
		for (int j = mid1; j < mid2; j++) {
			c[k++] = B[j].clone();
		}
		for (int i = tail; i < A.length; i++) {
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
			if (nearZero(reg(x))) {
				skipNext = true;
			}
			break;
		case SKIPNZ:
			if (!nearZero(reg(x))) {
				skipNext = true;
			}
			break;
		case SKIPGT:
			// A comparison in one line: "skip the next if x > y" did take a GT
			// into a scratch register and a SKIPNZ on it, two lines that had to
			// agree on a register for the decision to mean anything.
			if (reg(x) > reg(y)) {
				skipNext = true;
			}
			break;
		case SKIPLT:
			if (reg(x) < reg(y)) {
				skipNext = true;
			}
			break;
		case SEEKWATER:
			// The drink reflex as one instruction. It took nine: sense thirst,
			// compare it with a line, build 10 from 4 + 4 + 2 (water's band on
			// A_SEEK is past the constant pool), skip, move -- and any of the
			// nine broken, by a random pad or a mutation, lost the reflex. In
			// 2000 padded founders 13% still steered to water when parched,
			// and 42% of hunter cubs died of thirst.
			//
			// Rx = 1: the intent is complete, hydration is above the threshold
			// in Ry, and nothing is written. Rx = 0: it is not, and the seek
			// is set to water. A skip on Rx keeps a later seek write from
			// overriding the drink.
			//
			// The threshold is clamped to the range hydration lives in. A random
			// line reads whatever its register holds -- a done flag at 1, a full
			// throttle, a counter in the thousands -- and on the live world a
			// threshold out of range was a hunter seeking water it did not need.
			//
			// And completion is fuzzy: within waterTolerance() of the threshold
			// is near enough. Clamped alone, a threshold of 1 still sent a body
			// with any thirst at all to the water, where it stood without
			// drinking, since a body starts drinking only at DRINK_ONSET.
			double hydration = s.length > AgentIO.S_THIRST ? 1.0 - s[AgentIO.S_THIRST] : 1.0;
			double threshold = Math.max(0.0, Math.min(1.0, reg(y)));
			if (hydration + waterTolerance() > threshold) {
				set(x, 1);
			} else {
				if (act.length > AgentIO.A_SEEK) {
					act[AgentIO.A_SEEK] = AgentIO.WATER_INTENT;
				}
				set(x, 0);
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
			reg[CARRY] = reg(y); // what was written is passed on, like any result
			break;
		default: // NOP
			break;
		}
	}

	/** What a skip counts as zero: within {@link #ZERO_BAND} of it. */
	static boolean nearZero(double v) {
		return Math.abs(v) < ZERO_BAND;
	}

	private double reg(int i) {
		return reg[imod(i, NUM_REG)];
	}

	private void set(int i, double v) {
		if (Double.isNaN(v)) {
			v = 0;
		}
		double c = v < -REG_CLAMP ? -REG_CLAMP : (v > REG_CLAMP ? REG_CLAMP : v);
		reg[imod(i, NUM_REG)] = c;
		reg[CARRY] = c;
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

	/** A copy of the heritable program (each row {@code {op, x, y, z}}), for
	 *  exporting/serializing a genome. A copy, so a caller can't mutate the live
	 *  program. Reconstruct with {@link #Brain(int[][])}. */
	public int[][] code() {
		int[][] out = new int[code.length][];
		for (int i = 0; i < code.length; i++) {
			out[i] = code[i].clone();
		}
		return out;
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
				body = "skip next if R" + imod(x, NUM_REG) + " ≈ 0";
				break;
			case SKIPNZ:
				body = "skip next if R" + imod(x, NUM_REG) + " ≉ 0";
				break;
			case SKIPGT:
				body = "skip next if R" + imod(x, NUM_REG) + " > R" + imod(y, NUM_REG);
				break;
			case SKIPLT:
				body = "skip next if R" + imod(x, NUM_REG) + " < R" + imod(y, NUM_REG);
				break;
			case SEEKWATER:
				body = "R" + imod(x, NUM_REG) + " = seek water until hydration > R" + imod(y, NUM_REG) + " - " + waterTolerance();
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

	/** A uniformly random instruction: what an insertion adds, and what a
	 *  founder's padding is made of. */
	public static int[] randomInstruction() {
		return randInstr();
	}

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
