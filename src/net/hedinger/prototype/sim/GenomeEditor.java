package net.hedinger.prototype.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.hedinger.prototype.entities.AgentIO;
import net.hedinger.prototype.entities.Brain;
import net.hedinger.prototype.entities.GeneSchema;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.GenomeCodec;

/**
 * The genome editor's model: what a genome is made of, taken apart for a form
 * and put back together from one. Three operations, all pure.
 *
 * <ul>
 * <li>{@link #schema()} — every body gene with its unit, bounds and drift, the
 * clades, and the mind's instruction set: the opcodes, what each operand of
 * each opcode names (a register, a sensor, an actuator, a constant), and the
 * sensor and actuator names themselves. The editor builds its form from this
 * and nothing else, so a gene added to {@link GeneSchema} or a channel added to
 * {@link AgentIO} appears in the editor without touching it.</li>
 * <li>{@link #describe(String)} — an encoded genome as the form sees it: gene
 * values by key, the clade, the program as rows of four fields, and its
 * disassembly.</li>
 * <li>{@link #assemble} — the form's values back into an encoded genome. The
 * encoding stays {@link GenomeCodec}'s: the editor edits numbers, never text,
 * so there is no second serialisation and no syntax to get wrong. Values go
 * through the schema's own clamp, the same one a birth applies, so an edited
 * creature is one the world could have bred — unless the caller asks for raw
 * values, which is an experiment and says so.</li>
 * </ul>
 *
 * <p>Assembly starts from a base genome when one is given, so anything the
 * form does not show (a network mind, a field added later) rides through
 * unchanged rather than being silently dropped.
 */
public final class GenomeEditor {
	private GenomeEditor() {
	}

	/** Operand kinds: what each of the three fields of an instruction names,
	 *  by opcode. Empty means the field is unused. Mirrors {@code Brain.exec}. */
	public static final String REG = "reg", CONST = "const", SENSOR = "sensor", ACT = "act", NONE = "";
	private static final String[][] OPERANDS = {
			{ NONE, NONE, NONE }, // NOP
			{ REG, CONST, NONE }, // SET
			{ REG, REG, NONE }, // MOV
			{ REG, REG, REG }, // ADD
			{ REG, REG, REG }, // SUB
			{ REG, REG, REG }, // MUL
			{ REG, REG, REG }, // MIN
			{ REG, REG, REG }, // MAX
			{ REG, REG, NONE }, // NEG
			{ REG, REG, NONE }, // TANH
			{ REG, REG, REG }, // GT
			{ REG, NONE, NONE }, // SKIPZ
			{ REG, NONE, NONE }, // SKIPNZ
			{ REG, SENSOR, NONE }, // SENSE
			{ ACT, REG, NONE }, // WRITE
	};

	/** What the editor is made of: genes, clades and the instruction set. */
	public static Map<String, Object> schema() {
		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, Object>> genes = new ArrayList<>();
		for (GeneSchema.Gene g : GeneSchema.genes()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("key", g.key);
			row.put("unit", g.unit);
			row.put("lo", g.lo);
			row.put("hi", Double.isInfinite(g.hi) ? null : g.hi);
			row.put("drift", g.drift.name());
			row.put("integral", g.integral);
			genes.add(row);
		}
		out.put("genes", genes);
		List<Map<String, Object>> clades = new ArrayList<>();
		for (Genome.Clade c : Genome.Clade.values()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("code", c.code());
			row.put("name", c.wireName());
			clades.add(row);
		}
		out.put("clades", clades);
		List<Map<String, Object>> ops = new ArrayList<>();
		for (int i = 0; i < Brain.NUM_OPS; i++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("op", i);
			row.put("name", Brain.OP_NAMES[i]);
			row.put("operands", List.of(OPERANDS[i]));
			ops.add(row);
		}
		out.put("ops", ops);
		out.put("registers", Brain.NUM_REG);
		out.put("consts", Brain.CONST);
		out.put("sensors", List.of(AgentIO.SENSOR_NAMES));
		out.put("actuators", List.of(AgentIO.ACT_NAMES));
		out.put("maxLen", Brain.MAX_LEN);
		return out;
	}

	/** An encoded genome, taken apart for the form. Throws
	 *  {@link IllegalArgumentException} on a malformed line. */
	public static Map<String, Object> describe(String encoded) {
		return describe(GenomeCodec.decode(encoded));
	}

	static Map<String, Object> describe(Genome g) {
		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, Object> genes = new LinkedHashMap<>();
		for (GeneSchema.Gene gene : GeneSchema.genes()) {
			genes.put(gene.key, gene.get(g));
		}
		out.put("genes", genes);
		out.put("clade", g.clade.code());
		out.put("cladeName", g.clade.wireName());
		out.put("sexual", g.isSexual());
		List<List<Integer>> code = new ArrayList<>();
		List<String> disasm = new ArrayList<>();
		if (g.brain != null) {
			for (int[] row : g.brain.code()) {
				List<Integer> r = new ArrayList<>();
				for (int f : row) {
					r.add(f);
				}
				code.add(r);
			}
			disasm = List.of(g.brain.disassemble(AgentIO.SENSOR_NAMES, AgentIO.ACT_NAMES));
		}
		out.put("code", code);
		out.put("disasm", disasm);
		out.put("network", g.mlp != null);
		out.put("genome", GenomeCodec.encode(g));
		return out;
	}

	/**
	 * Two genomes side by side, the way a code diff shows two files: every
	 * body gene with both values and how far apart they sit within the gene's
	 * own range, the clade and markers, and the two programs aligned row by
	 * row so a changed, inserted or deleted instruction stands out against the
	 * ones both share. At the top, one number.
	 *
	 * <p>The number is the mean of a body similarity and a mind similarity,
	 * each 0..1. A gene's distance is {@code |a - b|} over its schema range
	 * (or over the larger of the two values when the range is unbounded),
	 * capped at 1; the body similarity is one minus the mean distance. The
	 * mind similarity is the longest common subsequence of the two programs'
	 * instructions over the longer program's length, which is the fraction of
	 * a diff that is not coloured. Both are plain enough to check by eye, and
	 * both are reported alongside the mean so the mean never has to be trusted
	 * on its own. A different clade does not move the number: it is reported
	 * as its own fact, because a hunter and a grazer with the same genes are
	 * exactly as alike as the genes say, and as different as the clade says.
	 */
	public static Map<String, Object> compare(String a, String b) {
		Genome ga = GenomeCodec.decode(a), gb = GenomeCodec.decode(b);
		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, Object>> genes = new ArrayList<>();
		double dist = 0;
		int n = 0;
		for (GeneSchema.Gene gene : GeneSchema.genes()) {
			double va = gene.get(ga), vb = gene.get(gb);
			double span = Double.isInfinite(gene.hi) ? Math.max(Math.abs(va), Math.abs(vb)) : gene.hi - gene.lo;
			double d = span <= 0 ? (va == vb ? 0 : 1) : Math.min(1.0, Math.abs(va - vb) / span);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("key", gene.key);
			row.put("unit", gene.unit);
			row.put("a", va);
			row.put("b", vb);
			row.put("distance", d);
			genes.add(row);
			dist += d;
			n++;
		}
		double body = n == 0 ? 1 : 1 - dist / n;
		out.put("genes", genes);
		out.put("cladeA", ga.clade.wireName());
		out.put("cladeB", gb.clade.wireName());
		out.put("sameClade", ga.clade == gb.clade);
		out.put("markerSimilarity", ga.similarityTo(gb));
		// The programs, aligned by longest common subsequence over disassembled
		// rows -- the same text the inspector shows, so a row reads as equal
		// exactly when it would look equal.
		String[] da = ga.brain == null ? new String[0] : strip(ga.brain.disassemble(AgentIO.SENSOR_NAMES, AgentIO.ACT_NAMES));
		String[] db = gb.brain == null ? new String[0] : strip(gb.brain.disassemble(AgentIO.SENSOR_NAMES, AgentIO.ACT_NAMES));
		List<Map<String, Object>> rows = new ArrayList<>();
		int common = align(da, db, rows);
		int longer = Math.max(da.length, db.length);
		double mind = longer == 0 ? 1 : (double) common / longer;
		out.put("mind", rows);
		out.put("mindLengthA", da.length);
		out.put("mindLengthB", db.length);
		out.put("mindCommon", common);
		out.put("bodySimilarity", body);
		out.put("mindSimilarity", mind);
		out.put("similarity", (body + mind) / 2);
		return out;
	}

	private static String[] strip(String[] disasm) {
		String[] out = new String[disasm.length];
		for (int i = 0; i < disasm.length; i++) {
			out[i] = disasm[i].replaceFirst("^[> ]*\\d+\\s*", "");
		}
		return out;
	}

	/** Longest-common-subsequence alignment of two instruction listings into
	 *  diff rows ({@code equal}, {@code change}, {@code insert}, {@code delete},
	 *  each with the text on either side or null); returns the common count. */
	private static int align(String[] a, String[] b, List<Map<String, Object>> rows) {
		int[][] lcs = new int[a.length + 1][b.length + 1];
		for (int i = a.length - 1; i >= 0; i--) {
			for (int j = b.length - 1; j >= 0; j--) {
				lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
			}
		}
		int i = 0, j = 0, common = 0;
		List<String> pendingA = new ArrayList<>(), pendingB = new ArrayList<>();
		while (i < a.length || j < b.length) {
			if (i < a.length && j < b.length && a[i].equals(b[j])) {
				flush(rows, pendingA, pendingB);
				rows.add(row("equal", a[i], b[j]));
				i++;
				j++;
				common++;
			} else if (j < b.length && (i >= a.length || lcs[i][j + 1] >= lcs[i + 1][j])) {
				pendingB.add(b[j++]);
			} else {
				pendingA.add(a[i++]);
			}
		}
		flush(rows, pendingA, pendingB);
		return common;
	}

	/** A run of unmatched rows on both sides is shown as changes, paired in
	 *  order, with the longer side's remainder as inserts or deletes. */
	private static void flush(List<Map<String, Object>> rows, List<String> pa, List<String> pb) {
		int k = 0;
		for (; k < pa.size() && k < pb.size(); k++) {
			rows.add(row("change", pa.get(k), pb.get(k)));
		}
		for (int i = k; i < pa.size(); i++) {
			rows.add(row("delete", pa.get(i), null));
		}
		for (int i = k; i < pb.size(); i++) {
			rows.add(row("insert", null, pb.get(i)));
		}
		pa.clear();
		pb.clear();
	}

	private static Map<String, Object> row(String kind, String a, String b) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("kind", kind);
		r.put("a", a);
		r.put("b", b);
		return r;
	}

	/**
	 * The form's values put back together. {@code base} is an encoded genome to
	 * start from (null for a blank one); {@code genes} overwrite by key;
	 * {@code clade} is a clade code; {@code code} is the program as rows of four
	 * integers, or null to leave the base's mind alone. With {@code raw} false
	 * every gene goes through the schema's clamp, as at a birth.
	 *
	 * @return the same shape as {@link #describe}, plus {@code clamped}: the keys
	 *         whose value the schema moved.
	 * @throws IllegalArgumentException naming the first thing that is wrong
	 */
	public static Map<String, Object> assemble(String base, Map<String, ?> genes, Integer clade,
			List<? extends List<? extends Number>> code, boolean raw) {
		Genome g = base == null || base.isBlank() ? new Genome() : GenomeCodec.decode(base);
		Map<String, GeneSchema.Gene> byKey = new LinkedHashMap<>();
		for (GeneSchema.Gene gene : GeneSchema.genes()) {
			byKey.put(gene.key, gene);
		}
		List<String> clamped = new ArrayList<>();
		if (genes != null) {
			for (Map.Entry<String, ?> e : genes.entrySet()) {
				GeneSchema.Gene gene = byKey.get(e.getKey());
				if (gene == null) {
					throw new IllegalArgumentException("unknown gene: " + e.getKey());
				}
				if (!(e.getValue() instanceof Number n)) {
					throw new IllegalArgumentException("gene " + e.getKey() + " is not a number");
				}
				double v = n.doubleValue();
				if (Double.isNaN(v) || Double.isInfinite(v)) {
					throw new IllegalArgumentException("gene " + e.getKey() + " is not finite");
				}
				if (raw) {
					gene.setRaw(g, v);
				} else {
					gene.set(g, v);
					if (Math.abs(gene.get(g) - v) > 1e-12) {
						clamped.add(e.getKey());
					}
				}
			}
		}
		if (clade != null) {
			Genome.Clade found = null;
			for (Genome.Clade c : Genome.Clade.values()) {
				if (c.code() == clade) {
					found = c;
				}
			}
			if (found == null) { // ofCode falls back to the herd; an editor must not
				throw new IllegalArgumentException("unknown clade: " + clade);
			}
			g.clade = found;
		}
		if (code != null) {
			if (code.isEmpty()) {
				throw new IllegalArgumentException("a program needs at least one instruction");
			}
			if (code.size() > Brain.MAX_LEN) {
				throw new IllegalArgumentException("a program holds at most " + Brain.MAX_LEN
						+ " instructions, not " + code.size());
			}
			int[][] m = new int[code.size()][4];
			for (int r = 0; r < m.length; r++) {
				List<? extends Number> row = code.get(r);
				if (row == null || row.size() != 4) {
					throw new IllegalArgumentException("instruction " + r + " needs four fields");
				}
				for (int c = 0; c < 4; c++) {
					double v = row.get(c).doubleValue();
					if (v != Math.rint(v)) {
						throw new IllegalArgumentException("instruction " + r + " field " + c + " is not an integer");
					}
					m[r][c] = (int) v;
				}
			}
			g.brain = new Brain(m);
		}
		Map<String, Object> out = describe(g);
		out.put("clamped", clamped);
		return out;
	}
}
