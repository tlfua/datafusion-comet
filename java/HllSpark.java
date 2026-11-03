// Mirrors Spark 4.x HllSketchAgg / HllUnionAgg / HllUnion / HllSketchEstimate on DataSketches-Java,
// and generates the case corpus the Rust (Comet) side replays.
//
//   java HllSpark gen    <outDir>   -> cases.tsv, pool.tsv, pool/*.bin, java/*.bin
//   java HllSpark verify <outDir>   -> verify.tsv (Java reading the bytes Comet produced)

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.apache.datasketches.hll.HllSketch;
import org.apache.datasketches.hll.TgtHllType;
import org.apache.datasketches.hll.Union;
import org.apache.datasketches.memory.Memory;

public final class HllSpark {
  static final long GOLD = 0x9E3779B97F4A7C15L;
  static final long[] SPECIAL = {
    0L, -1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE,
    255L, 256L, -256L, 1L << 32, -(1L << 32)
  };
  static final int[] ALPHA = {
    'a', 'b', 'c', 'x', 'y', 'z', '0', '1', '9', ' ', '-', '_',
    0xE9, 'e', 0x301, 0x4E2D, 0x6587, 0xDF, 0x3042, 0x1F600, 0x1F680
  };

  // ---------------------------------------------------------------- inputs

  static long lv(long seed, long i) {
    return (seed * 1000003L + i) * GOLD;
  }

  static String sv(long seed, long i) {
    if (i % 97 == 13) {
      return "";
    }
    int len = (int) (1 + ((i * 7 + seed) % 40));
    StringBuilder sb = new StringBuilder();
    for (int j = 0; j < len; j++) {
      long h = (((i * 31 + j) * GOLD) + seed) >>> 33;
      sb.appendCodePoint(ALPHA[(int) (h % ALPHA.length)]);
    }
    sb.append(Long.toString(i, 36));
    return sb.toString();
  }

  interface Sink {
    void l(long v);

    void b(byte[] v, String s);
  }

  static void forEach(String kind, long seed, long lo, long hi, Sink sink) {
    for (long i = lo; i < hi; i++) {
      switch (kind) {
        case "long":
          sink.l(lv(seed, i));
          break;
        case "int":
          sink.l((long) (int) lv(seed, i));
          break;
        case "special":
          sink.l(SPECIAL[(int) (i % SPECIAL.length)]);
          break;
        case "str":
          String s = sv(seed, i);
          sink.b(s.getBytes(StandardCharsets.UTF_8), s);
          break;
        default:
          throw new IllegalArgumentException(kind);
      }
    }
  }

  // Spark 4.x: IntegerType/LongType -> update(long); StringType (UTF8_BINARY) -> update(bytes)
  // after skipping the empty string; BinaryType -> update(byte[]).
  static void feed(HllSketch sk, String kind, long seed, long lo, long hi) {
    forEach(kind, seed, lo, hi, new Sink() {
      public void l(long v) {
        sk.update(v);
      }

      public void b(byte[] v, String s) {
        if (v.length != 0) {
          sk.update(v);
        }
      }
    });
  }

  // Spark 3.5 style: update(String).
  static void feedString(HllSketch sk, long seed, long lo, long hi) {
    forEach("str", seed, lo, hi, new Sink() {
      public void l(long v) {
        throw new IllegalStateException();
      }

      public void b(byte[] v, String s) {
        sk.update(s);
      }
    });
  }

  static String checksum(String kind, long seed, long lo, long hi) {
    final long[] h = {0xcbf29ce484222325L};
    forEach(kind, seed, lo, hi, new Sink() {
      void mix(int b) {
        h[0] ^= (b & 0xff);
        h[0] *= 0x100000001b3L;
      }

      public void l(long v) {
        for (int k = 0; k < 8; k++) {
          mix((int) (v >>> (8 * k)));
        }
      }

      public void b(byte[] v, String s) {
        for (byte x : v) {
          mix(x);
        }
        mix(0xff);
      }
    });
    return Long.toHexString(h[0]);
  }

  static TgtHllType type(int t) {
    switch (t) {
      case 4:
        return TgtHllType.HLL_4;
      case 6:
        return TgtHllType.HLL_6;
      case 8:
        return TgtHllType.HLL_8;
      default:
        throw new IllegalArgumentException("type " + t);
    }
  }

  static int thr(int k) {
    return k < 8 ? 7 : 3 << (k - 5);
  }

  static int[] order(int n, boolean rev) {
    int[] o = new int[n];
    for (int i = 0; i < n; i++) {
      o[i] = rev ? n - 1 - i : i;
    }
    return o;
  }

  // ---------------------------------------------------------------- Spark mirrors

  static final TgtHllType TARGET = TgtHllType.HLL_8;

  /** HllSketchAgg partial: one buffer per partition, serialize = toUpdatableByteArray. */
  static byte[][] sketchAggPartials(int k, String kind, long seed, long start, long n, int p) {
    byte[][] out = new byte[p][];
    for (int q = 0; q < p; q++) {
      long lo = start + q * n / p;
      long hi = start + (q + 1) * n / p;
      HllSketch s = new HllSketch(k, TARGET);
      feed(s, kind, seed, lo, hi);
      out[q] = s.toUpdatableByteArray();
    }
    return out;
  }

  /** HllSketchAgg final: fresh buffer, deserialize = heapify, merge = Union(buf.k) of both. */
  static byte[] sketchAggFinal(int k, byte[][] partials, boolean rev) {
    HllSketch buf = new HllSketch(k, TARGET);
    for (int idx : order(partials.length, rev)) {
      HllSketch in = HllSketch.heapify(partials[idx]);
      Union u = new Union(buf.getLgConfigK());
      u.update(buf);
      u.update(in);
      buf = u.getResult(TARGET);
    }
    return buf.toUpdatableByteArray();
  }

  /** HllSketchEstimate. */
  static double est(byte[] b) {
    return HllSketch.heapify(Memory.wrap(b)).getEstimate();
  }

  /** HllUnion (scalar). */
  static byte[] hllUnion(byte[] a, byte[] b, boolean allow) {
    HllSketch s1 = HllSketch.heapify(Memory.wrap(a));
    HllSketch s2 = HllSketch.heapify(Memory.wrap(b));
    if (!allow && s1.getLgConfigK() != s2.getLgConfigK()) {
      throw new IllegalStateException("HLL_UNION_DIFFERENT_LG_K");
    }
    Union u = new Union(Math.min(s1.getLgConfigK(), s2.getLgConfigK()));
    u.update(s1);
    u.update(s2);
    return u.getResult(TARGET).toUpdatableByteArray();
  }

  /** HllUnionAgg partial: lazily create Union(first k), wrap each input, serialize. */
  static byte[] unionAggPartial(List<byte[]> items, boolean allow) {
    Union u = null;
    for (byte[] b : items) {
      HllSketch sk = HllSketch.wrap(Memory.wrap(b));
      if (u == null) {
        u = new Union(sk.getLgConfigK());
      }
      if (!allow && u.getLgConfigK() != sk.getLgConfigK()) {
        throw new IllegalStateException("HLL_UNION_DIFFERENT_LG_K");
      }
      u.update(sk);
    }
    return u == null ? new byte[0] : u.toUpdatableByteArray();
  }

  /** HllUnionAgg final: buffer None, deserialize = Union.heapify, merge per Spark. */
  static byte[] unionAggFinal(byte[][] partials, boolean rev, boolean allow) {
    Union buf = null;
    for (int idx : order(partials.length, rev)) {
      byte[] pb = partials[idx];
      Union in = pb.length != 0 ? Union.heapify(pb) : null;
      if (buf != null && in != null) {
        if (!allow && buf.getLgConfigK() != in.getLgConfigK()) {
          throw new IllegalStateException("HLL_UNION_DIFFERENT_LG_K");
        }
        buf.update(in.getResult(TARGET));
      } else if (buf == null && in != null) {
        buf = in;
      }
    }
    return buf != null ? buf.toUpdatableByteArray() : new Union().toUpdatableByteArray();
  }

  // ---------------------------------------------------------------- output helpers

  static Path out;
  static BufferedWriter cases;
  static BufferedWriter poolW;
  static final Map<String, String> pool = new LinkedHashMap<>();
  static final Map<String, Integer> poolK = new LinkedHashMap<>();
  static int seq = 0;

  static void write(Path p, byte[] b) throws IOException {
    Files.write(p, b);
  }

  static String bits(double d) {
    return Long.toHexString(Double.doubleToRawLongBits(d));
  }

  static String mode(byte[] b) {
    if (b.length < 8) {
      return "EMPTYBYTES";
    }
    switch (b[7] & 3) {
      case 0:
        return "LIST";
      case 1:
        return "SET";
      default:
        return "HLL";
    }
  }

  static String estFields(String prefix, byte[] b) {
    double e = est(b);
    return "\t" + prefix + "_est=" + e + "\t" + prefix + "_bits=" + bits(e) + "\t" + prefix
        + "_round=" + Math.round(e) + "\t" + prefix + "_mode=" + mode(b);
  }

  static void line(String s) throws IOException {
    cases.write(s);
    cases.write('\n');
  }

  static String nextId(String set) {
    return String.format("%s%05d", set, seq++);
  }

  /** maker raw: a single sketch of the given type; maker agg: Spark hll_sketch_agg output. */
  static String poolId(int k, int t, long n, long seed, long start, String maker)
      throws IOException {
    String key = k + "/" + t + "/" + n + "/" + seed + "/" + start + "/" + maker;
    String pid = pool.get(key);
    if (pid != null) {
      return pid;
    }
    pid = String.format("P%05d", pool.size());
    byte[] upd;
    byte[] cmp;
    if (maker.equals("raw")) {
      HllSketch s = new HllSketch(k, type(t));
      feed(s, "long", seed, start, start + n);
      upd = s.toUpdatableByteArray();
      cmp = s.toCompactByteArray();
    } else {
      if (t != 8) {
        throw new IllegalArgumentException("agg maker is HLL_8 only");
      }
      upd = sketchAggFinal(k, sketchAggPartials(k, "long", seed, start, n, 1), false);
      cmp = HllSketch.heapify(upd).toCompactByteArray();
    }
    write(out.resolve("pool/" + pid + ".upd.bin"), upd);
    write(out.resolve("pool/" + pid + ".cmp.bin"), cmp);
    poolW.write(pid + "\tlgk=" + k + "\ttype=" + t + "\tn=" + n + "\tseed=" + seed + "\tstart="
        + start + "\tmaker=" + maker + "\tck=" + checksum("long", seed, start, start + n) + "\n");
    pool.put(key, pid);
    poolK.put(pid, k);
    return pid;
  }

  static byte[] poolBytes(String pid, String form) throws IOException {
    return Files.readAllBytes(out.resolve("pool/" + pid + "." + form + ".bin"));
  }

  // ---------------------------------------------------------------- sets

  /** A: hll_sketch_estimate(hll_sketch_agg(x, k)) through partial/final with P partitions. */
  static void setA() throws IOException {
    for (int k = 4; k <= 21; k++) {
      TreeSet<Long> ns = new TreeSet<>(Arrays.asList(0L, 1L, 2L, 7L, 8L, 9L, 24L, 25L, 100L,
          (long) thr(k) - 1, (long) thr(k), (long) thr(k) + 1, 1000L, 10000L, 100000L));
      if (k == 4 || k == 8 || k == 12 || k == 16 || k == 21) {
        ns.add(2000000L);
      }
      for (long n : ns) {
        for (int p : new int[] {1, 3, 8}) {
          for (boolean rev : new boolean[] {false, true}) {
            caseA(k, "long", 1, n, p, rev);
          }
        }
      }
    }
    for (int k : new int[] {4, 12, 21}) {
      for (long n : new long[] {0, 1, 9, thr(k) + 1, 1000, 100000}) {
        caseA(k, "int", 2, n, 3, false);
      }
      for (int p : new int[] {1, 3}) {
        caseA(k, "special", 0, 12, p, false);
      }
    }
    for (int k : new int[] {4, 8, 11, 12, 13, 21}) {
      for (long n : new long[] {1, 9, 25, thr(k) + 1, 1000, 100000}) {
        for (int p : new int[] {1, 8}) {
          for (boolean rev : new boolean[] {false, true}) {
            caseA(k, "str", 3, n, p, rev);
          }
        }
      }
    }
  }

  static void caseA(int k, String kind, long seed, long n, int p, boolean rev) throws IOException {
    String id = nextId("A");
    byte[] upd = sketchAggFinal(k, sketchAggPartials(k, kind, seed, 0, n, p), rev);
    byte[] cmp = HllSketch.heapify(upd).toCompactByteArray();
    write(out.resolve("java/" + id + ".upd.bin"), upd);
    write(out.resolve("java/" + id + ".cmp.bin"), cmp);
    String extra = "";
    if (kind.equals("str")) {
      HllSketch a = new HllSketch(k, TARGET);
      feed(a, kind, seed, 0, n);
      HllSketch b = new HllSketch(k, TARGET);
      feedString(b, seed, 0, n);
      extra = "\tstr_api_same=" + (Arrays.equals(a.toUpdatableByteArray(), b.toUpdatableByteArray()) ? 1 : 0);
    }
    line(id + "\tset=A\tlgk=" + k + "\tkind=" + kind + "\tseed=" + seed + "\tn=" + n + "\tP=" + p
        + "\trev=" + (rev ? 1 : 0) + "\tck=" + checksum(kind, seed, 0, n) + estFields("j", upd)
        + extra);
  }

  /** B: hll_sketch_estimate on third-party Java sketches (HLL_4/6/8, updatable/compact). */
  static void setB() throws IOException {
    for (int k = 4; k <= 21; k++) {
      TreeSet<Long> ns = new TreeSet<>(Arrays.asList(0L, 1L, 7L, 8L, 9L, 25L,
          (long) thr(k) + 1, 1000L, 100000L));
      if (k == 12 || k == 21) {
        ns.add(2000000L);
      }
      for (int t : new int[] {4, 6, 8}) {
        for (long n : ns) {
          String pid = poolId(k, t, n, 1, 0, "raw");
          for (String form : new String[] {"upd", "cmp"}) {
            String id = nextId("B");
            line(id + "\tset=B\tpool=" + pid + "\tform=" + form + "\tlgk=" + k + "\ttype=" + t
                + "\tn=" + n + estFields("j", poolBytes(pid, form)));
          }
        }
      }
    }
  }

  /** C: hll_union(a, b, allow). */
  static void setC() throws IOException {
    // The 18 combinations from https://github.com/apache/datafusion-comet/pull/7402 .
    for (int t : new int[] {4, 6, 8}) {
      for (long n : new long[] {1000, 100000, 2000000}) {
        for (String form : new String[] {"upd", "cmp"}) {
          String a = poolId(12, t, n, 1, 0, "raw");
          String b = poolId(12, 8, n, 1, n, "raw");
          caseC("andy18", a, form, b, "upd", true);
        }
      }
    }
    int[][] lps = {{12, 12}, {4, 4}, {21, 21}, {8, 9}, {12, 10}, {10, 12}, {4, 21}, {21, 4},
        {16, 13}, {5, 7}};
    int[][] tps = {{8, 8}, {4, 8}, {6, 4}, {4, 4}, {6, 6}};
    String[][] fps = {{"upd", "upd"}, {"cmp", "cmp"}, {"upd", "cmp"}};
    for (int[] lp : lps) {
      long[][] nps = {{0, 0}, {1, 1}, {9, 25}, {1000, 1000}, {100000, 1000}, {1000, 100000},
          {100000, 100000}, {thr(lp[0]) + 1, 7}};
      for (int[] tp : tps) {
        for (long[] np : nps) {
          for (String[] fp : fps) {
            String a = poolId(lp[0], tp[0], np[0], 1, 0, "raw");
            String b = poolId(lp[1], tp[1], np[1], 1, np[0] / 2, "raw");
            caseC("general", a, fp[0], b, fp[1], true);
          }
        }
      }
      String a = poolId(lp[0], 8, 1000, 1, 0, "raw");
      String b = poolId(lp[1], 8, 1000, 1, 500, "raw");
      caseC("noallow", a, "upd", b, "upd", false);
    }
  }

  static void caseC(String group, String a, String af, String b, String bf, boolean allow)
      throws IOException {
    String id = nextId("C");
    String head = id + "\tset=C\tgroup=" + group + "\ta=" + a + "\taf=" + af + "\tb=" + b
        + "\tbf=" + bf + "\tallow=" + (allow ? 1 : 0) + "\tka=" + poolK.get(a) + "\tkb="
        + poolK.get(b);
    try {
      byte[] r = hllUnion(poolBytes(a, af), poolBytes(b, bf), allow);
      write(out.resolve("java/" + id + ".upd.bin"), r);
      write(out.resolve("java/" + id + ".cmp.bin"), HllSketch.heapify(r).toCompactByteArray());
      line(head + "\tj_err=0" + estFields("j", r));
    } catch (RuntimeException e) {
      line(head + "\tj_err=1\tj_msg=" + e.getClass().getSimpleName() + ":" + e.getMessage());
    }
  }

  /** D: hll_union_agg over stored sketches, partial/final with P partitions. */
  static void setD() throws IOException {
    List<Object[]> configs = new ArrayList<>();
    long[] ns1 = {0, 1, 50, 500, 3000, 3000, 20000, 100000, 7, 250000};
    configs.add(new Object[] {"k12_agg", items(12, 8, ns1, "agg"), false});
    configs.add(new Object[] {"k4_agg", items(4, 8, ns1, "agg"), false});
    configs.add(new Object[] {"k21_agg", items(21, 8, new long[] {0, 10, 1000, 100000, 300000}, "agg"), false});
    configs.add(new Object[] {"mixk_agg", mixK(new int[] {12, 10, 14, 12, 4, 21, 12},
        new long[] {1000, 50, 100000, 7, 3000, 200000, 0}), true});
    List<String> mixT = new ArrayList<>();
    int[] ts = {4, 6, 8, 4, 6, 8};
    long[] nt = {1000, 100000, 50, 2000000, 0, 9};
    for (int i = 0; i < ts.length; i++) {
      mixT.add(poolId(12, ts[i], nt[i], 7, i * 1000L, "raw"));
    }
    configs.add(new Object[] {"mixtype_raw", mixT, false});
    configs.add(new Object[] {"mixk_noallow", mixK(new int[] {12, 12, 10, 12},
        new long[] {1000, 2000, 3000, 4000}), false});
    configs.add(new Object[] {"all_empty", items(12, 8, new long[] {0, 0, 0}, "agg"), false});
    configs.add(new Object[] {"no_rows", new ArrayList<String>(), false});
    for (Object[] c : configs) {
      @SuppressWarnings("unchecked")
      List<String> its = (List<String>) c[1];
      boolean allow = (Boolean) c[2];
      boolean allAgg = true;
      for (String pid : its) {
        allAgg &= pid.startsWith("P") && aggPids.contains(pid);
      }
      for (int p : new int[] {1, 3, 8}) {
        for (boolean rev : new boolean[] {false, true}) {
          for (String src : allAgg ? new String[] {"java", "rust"} : new String[] {"java"}) {
            caseD((String) c[0], its, p, rev, allow, src);
          }
        }
      }
    }
  }

  static final LinkedHashSet<String> aggPids = new LinkedHashSet<>();

  static List<String> items(int k, int t, long[] ns, String maker) throws IOException {
    List<String> l = new ArrayList<>();
    for (int i = 0; i < ns.length; i++) {
      String pid = poolId(k, t, ns[i], 7, i * 1000L, maker);
      if (maker.equals("agg")) {
        aggPids.add(pid);
      }
      l.add(pid);
    }
    return l;
  }

  static List<String> mixK(int[] ks, long[] ns) throws IOException {
    List<String> l = new ArrayList<>();
    for (int i = 0; i < ks.length; i++) {
      String pid = poolId(ks[i], 8, ns[i], 7, i * 1000L, "agg");
      aggPids.add(pid);
      l.add(pid);
    }
    return l;
  }

  static void caseD(String cfg, List<String> its, int p, boolean rev, boolean allow, String src)
      throws IOException {
    String id = nextId("D");
    String head = id + "\tset=D\tcfg=" + cfg + "\titems=" + (its.isEmpty() ? "-" : String.join(",", its))
        + "\tP=" + p + "\trev=" + (rev ? 1 : 0) + "\tallow=" + (allow ? 1 : 0) + "\tsrc=" + src;
    try {
      int m = its.size();
      byte[][] partials = new byte[p][];
      for (int q = 0; q < p; q++) {
        List<byte[]> part = new ArrayList<>();
        for (int i = q * m / p; i < (q + 1) * m / p; i++) {
          part.add(poolBytes(its.get(i), "upd"));
        }
        partials[q] = unionAggPartial(part, allow);
      }
      byte[] r = unionAggFinal(partials, rev, allow);
      write(out.resolve("java/" + id + ".upd.bin"), r);
      write(out.resolve("java/" + id + ".cmp.bin"), HllSketch.heapify(r).toCompactByteArray());
      line(head + "\tj_err=0" + estFields("j", r));
    } catch (RuntimeException e) {
      line(head + "\tj_err=1\tj_msg=" + e.getClass().getSimpleName() + ":" + e.getMessage());
    }
  }

  // ---------------------------------------------------------------- verify

  static Map<String, String> kv(String l) {
    Map<String, String> m = new LinkedHashMap<>();
    String[] f = l.split("\t");
    m.put("id", f[0]);
    for (int i = 1; i < f.length; i++) {
      int e = f[i].indexOf('=');
      m.put(f[i].substring(0, e), f[i].substring(e + 1));
    }
    return m;
  }

  static void verify() throws IOException {
    try (BufferedReader r = Files.newBufferedReader(out.resolve("rust.tsv"));
        BufferedWriter w = Files.newBufferedWriter(out.resolve("verify.tsv"))) {
      String l;
      while ((l = r.readLine()) != null) {
        Map<String, String> m = kv(l);
        String id = m.get("id");
        StringBuilder sb = new StringBuilder(id);
        for (String suffix : new String[] {"bin", "xreser.bin"}) {
          Path rp = out.resolve("rust/" + id + "." + suffix);
          if (!Files.exists(rp)) {
            continue;
          }
          String tag = suffix.equals("bin") ? "v" : "vx";
          byte[] rb = Files.readAllBytes(rp);
          try {
            double e = est(rb);
            sb.append("\t" + tag + "_bits=" + bits(e) + "\t" + tag + "_round=" + Math.round(e));
            Path jp = out.resolve("java/" + id + ".upd.bin");
            if (Files.exists(jp)) {
              byte[] reh = HllSketch.heapify(rb).toUpdatableByteArray();
              sb.append("\t" + tag + "_upd_same="
                  + (Arrays.equals(reh, Files.readAllBytes(jp)) ? 1 : 0));
            }
          } catch (RuntimeException ex) {
            sb.append("\t" + tag + "_err=" + ex.getClass().getSimpleName());
          }
        }
        w.write(sb.toString());
        w.write('\n');
      }
    }
  }

  public static void main(String[] args) throws IOException {
    out = Paths.get(args[1]);
    if (args[0].equals("gen")) {
      Files.createDirectories(out.resolve("pool"));
      Files.createDirectories(out.resolve("java"));
      try (BufferedWriter c = Files.newBufferedWriter(out.resolve("cases.tsv"));
          BufferedWriter pw = Files.newBufferedWriter(out.resolve("pool.tsv"))) {
        cases = c;
        poolW = pw;
        setA();
        setB();
        setC();
        setD();
      }
      System.out.println("cases=" + seq + " pool=" + pool.size());
    } else {
      verify();
    }
  }
}
