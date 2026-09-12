package net.hedinger.prototype.entities;

import net.hedinger.prototype.engine.Utils;

/**
 * A creature's evolvable mind as a fixed-topology neural network — the second
 * decision substrate, competing against the LGP {@link Brain} behind the very
 * same {@link AgentIO} contract.
 *
 * <p>Where the LGP brain is a sparse, interpretable program of variable length
 * that thinks one instruction at a time, this is a dense continuous policy that
 * reads the whole sensor vector and writes the whole actuator vector every
 * tick, in one forward pass: {@code act = W2 · tanh(W1 · sensors + b1) + b2}.
 * Its heritable part is the flat weight vector; crossover mixes two parents'
 * weights and mutation jitters them, so the network evolves exactly as body
 * size does. The two substrates run in one world through the same seam, which
 * is the whole point — it lets the question "does a dense continuous policy
 * out-evolve a sparse program under this economy?" be asked by selection rather
 * than argued.
 *
 * <p>Every network is a valid policy (there are no illegal weights), and the
 * output layer is linear so the body's own clamps and gates decide what an
 * actuator value means — the same division of labour the LGP brain has. Fresh
 * random networks carry a small <b>forage prior</b> in the output bias (seek
 * food, at a moderate throttle) for the reason the LGP cohort ships a starter
 * brain rather than random code: a policy that cannot feed leaves selection no
 * gradient to climb, so the prior is the warm seed evolution sharpens from.
 */
public final class MlpBrain {

	/** Hidden units. Small on purpose: a compact policy is cheap to run for a
	 *  whole cohort and keeps the heritable weight vector a manageable length. */
	public static final int HIDDEN = 8;

	/** Spread of a fresh network's random weights (uniform on ±this). */
	private static final double INIT_W = 0.4;

	private final int in, hid, out;
	private final double[] w1; // hid × in
	private final double[] b1; // hid
	private final double[] w2; // out × hid
	private final double[] b2; // out
	private final double[] hidden; // scratch, reused each forward pass

	private MlpBrain(int in, int hid, int out, double[] w1, double[] b1, double[] w2, double[] b2) {
		this.in = in;
		this.hid = hid;
		this.out = out;
		this.w1 = w1;
		this.b1 = b1;
		this.w2 = w2;
		this.b2 = b2;
		this.hidden = new double[hid];
	}

	/** A network sized to the live sensor and actuator vectors. */
	public static MlpBrain sized() {
		return new MlpBrain(AgentIO.NUM_SENSORS, HIDDEN, AgentIO.NUM_ACT,
				new double[HIDDEN * AgentIO.NUM_SENSORS], new double[HIDDEN],
				new double[AgentIO.NUM_ACT * HIDDEN], new double[AgentIO.NUM_ACT]);
	}

	/** A fresh random network with the forage prior baked into its output bias
	 *  (seeded RNG, so the deterministic stream is preserved). */
	public static MlpBrain random() {
		MlpBrain m = sized();
		for (int i = 0; i < m.w1.length; i++) {
			m.w1[i] = rand();
		}
		for (int i = 0; i < m.w2.length; i++) {
			m.w2[i] = rand();
		}
		// The forage-and-breed prior: absent any input signal the network already
		// asks to seek food at a moderate pace and to reproduce when it can afford
		// to — so a random founder not only feeds but establishes a lineage, and
		// selection has a breeding population to shape rather than a body that lives
		// alone and dies childless. The same warm seed the LGP cohort gets from its
		// starter brain (forage, and breed when able), expressed as output biases;
		// the mate wish only fires when the body is actually fertile.
		m.b2[AgentIO.A_SEEK] = 0.1; // SEEK_FORAGE band
		m.b2[AgentIO.A_THROTTLE] = 0.3; // a cheap amble
		m.b2[AgentIO.A_MATE] = 0.6; // breed when fertile (gated > 0.5)
		return m;
	}

	/** One forward pass: reads {@code sensors}, writes every {@code actuators}
	 *  slot. Hidden layer tanh, output linear (the body clamps and gates). */
	public void forward(double[] sensors, double[] actuators) {
		for (int h = 0; h < hid; h++) {
			double sum = b1[h];
			int base = h * in;
			int n = Math.min(in, sensors.length);
			for (int i = 0; i < n; i++) {
				sum += w1[base + i] * sensors[i];
			}
			hidden[h] = Math.tanh(sum);
		}
		int m = Math.min(out, actuators.length);
		for (int o = 0; o < m; o++) {
			double sum = b2[o];
			int base = o * hid;
			for (int h = 0; h < hid; h++) {
				sum += w2[base + h] * hidden[h];
			}
			actuators[o] = sum;
		}
	}

	/** A deep copy — heredity hands each child its own weights. */
	public MlpBrain copy() {
		return new MlpBrain(in, hid, out, w1.clone(), b1.clone(), w2.clone(), b2.clone());
	}

	/** Per-weight crossover of two parents, then mutation — the network evolves
	 *  the way every magnitude gene does. */
	public static MlpBrain child(MlpBrain a, MlpBrain b, double rate) {
		MlpBrain c = a.copy();
		cross(c.w1, a.w1, b.w1);
		cross(c.b1, a.b1, b.b1);
		cross(c.w2, a.w2, b.w2);
		cross(c.b2, a.b2, b.b2);
		c.mutate(rate);
		return c;
	}

	/** Jitters every weight by up to ±rate (absolute), clamped so the network
	 *  cannot run away to extremes over a long world. */
	public void mutate(double rate) {
		jitter(w1, rate);
		jitter(b1, rate);
		jitter(w2, rate);
		jitter(b2, rate);
	}

	/** The whole weight vector, flattened, for the codec — w1, b1, w2, b2 in
	 *  order. Reconstruct with {@link #fromWeights(double[])}. */
	public double[] weights() {
		double[] all = new double[w1.length + b1.length + w2.length + b2.length];
		int k = 0;
		k = put(all, k, w1);
		k = put(all, k, b1);
		k = put(all, k, w2);
		put(all, k, b2);
		return all;
	}

	/** Rebuilds a network from {@link #weights()} against the live topology; a
	 *  vector of the wrong length is rejected, the honest outcome for a saved
	 *  network the sensor/actuator vectors have grown past. */
	public static MlpBrain fromWeights(double[] all) {
		MlpBrain m = sized();
		int need = m.w1.length + m.b1.length + m.w2.length + m.b2.length;
		if (all.length != need) {
			throw new IllegalArgumentException("mlp weight count " + all.length
					+ " != topology " + need);
		}
		int k = 0;
		k = take(all, k, m.w1);
		k = take(all, k, m.b1);
		k = take(all, k, m.w2);
		take(all, k, m.b2);
		return m;
	}

	/** Weight count — a fixed cost, for the inspector and any capability pricing. */
	public int size() {
		return w1.length + b1.length + w2.length + b2.length;
	}

	/** A one-line summary for the mind inspector. */
	public String[] describe() {
		return new String[] {
				String.format("MLP %d→%d→%d  (%d weights, linear out)", in, hid, out, size()),
		};
	}

	private static double rand() {
		return (Utils.random() * 2 - 1) * INIT_W;
	}

	private static void jitter(double[] a, double rate) {
		for (int i = 0; i < a.length; i++) {
			double v = a[i] + (Utils.random() * 2 - 1) * rate;
			a[i] = v < -CLAMP ? -CLAMP : (v > CLAMP ? CLAMP : v);
		}
	}

	private static final double CLAMP = 8.0;

	private static void cross(double[] c, double[] a, double[] b) {
		for (int i = 0; i < c.length; i++) {
			c[i] = Utils.random() < 0.5 ? a[i] : b[i];
		}
	}

	private static int put(double[] dst, int k, double[] src) {
		System.arraycopy(src, 0, dst, k, src.length);
		return k + src.length;
	}

	private static int take(double[] src, int k, double[] dst) {
		System.arraycopy(src, k, dst, 0, dst.length);
		return k + dst.length;
	}
}
