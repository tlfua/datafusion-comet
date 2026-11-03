#!/usr/bin/env python3
"""Join cases.tsv (Java = Spark), rust.tsv (Comet) and verify.tsv (Java reading Comet bytes)
and print a Markdown report."""

import collections
import os
import struct
import sys


def load(path):
    rows = {}
    with open(path) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            d = {}
            for p in parts[1:]:
                k, v = p.split("=", 1)
                d[k] = v
            rows[parts[0]] = d
    return rows


def as_double(bits_hex):
    return struct.unpack("<d", struct.pack("<Q", int(bits_hex, 16)))[0]


def same_file(a, b):
    if not (os.path.exists(a) and os.path.exists(b)):
        return None
    with open(a, "rb") as fa, open(b, "rb") as fb:
        return fa.read() == fb.read()


def main(out, label):
    cases = load(os.path.join(out, "cases.tsv"))
    rust = load(os.path.join(out, "rust.tsv"))
    ver = load(os.path.join(out, "verify.tsv"))

    stats = collections.defaultdict(collections.Counter)
    maxrel = collections.defaultdict(float)
    bad = []

    def chk(group, name, ok, cid, detail=""):
        stats[group][name + (" ok" if ok else " FAIL")] += 1
        if not ok:
            bad.append((group, name, cid, detail))

    for cid, c in cases.items():
        r = rust.get(cid, {})
        v = ver.get(cid, {})
        s = c["set"]
        if s == "A":
            group = "A %s %s" % (c["kind"], c.get("j_mode"))
        elif s == "B":
            group = "B HLL_%s %s %s" % (c["type"], c["form"], c.get("j_mode"))
        elif s == "C":
            group = "C %s" % c["group"]
        else:
            group = "D %s src=%s" % (c["cfg"], c["src"])
        stats[group]["cases"] += 1
        desc = " ".join("%s=%s" % (k, c[k]) for k in c if not k.startswith("j_"))

        if s == "A":
            chk(group, "input checksum", r.get("ck_ok") == "1", cid, desc)
            if c["kind"] == "str":
                chk(group, "Java update(String)==update(bytes)", c.get("str_api_same") == "1", cid, desc)

        j_err = c.get("j_err", "0") == "1"
        r_err = r.get("r_err", "0") == "1"
        if s in ("C", "D"):
            chk(group, "error agrees", j_err == r_err, cid,
                "%s java_err=%s rust_err=%s %s %s" % (desc, j_err, r_err, c.get("j_msg", ""), r.get("r_msg", "")))
            if j_err or r_err:
                stats[group]["both error" if (j_err and r_err) else "one error"] += 1
                continue

        if "r_bits" not in r:
            chk(group, "Comet produced a result", False, cid, desc + " " + str(r))
            continue
        jb, rb = c["j_bits"], r["r_bits"]
        chk(group, "estimate bit-identical", jb == rb, cid,
            "%s java=%s comet=%s" % (desc, c["j_est"], r.get("r_est")))
        jd, rd = as_double(jb), as_double(rb)
        rel = abs(jd - rd) / max(abs(jd), 1.0)
        maxrel[group] = max(maxrel[group], rel)
        chk(group, "estimate within 1e-6", rel <= 1e-6, cid, "%s rel=%g" % (desc, rel))
        chk(group, "hll_sketch_estimate (rounded) equal", c["j_round"] == r["r_round"], cid,
            "%s java=%s comet=%s" % (desc, c["j_round"], r["r_round"]))

        if s in ("A", "C", "D"):
            rbin = os.path.join(out, "rust", cid + ".bin")
            same_cmp = same_file(rbin, os.path.join(out, "java", cid + ".cmp.bin"))
            same_upd = same_file(rbin, os.path.join(out, "java", cid + ".upd.bin"))
            chk(group, "Comet bytes == Java compact bytes", bool(same_cmp), cid, desc)
            stats[group]["Comet bytes == Spark (updatable) bytes: %s" % bool(same_upd)] += 1
            chk(group, "Spark reads Comet bytes: same estimate",
                v.get("v_round") == c["j_round"] and v.get("v_bits") == jb, cid,
                "%s spark_on_comet=%s java=%s" % (desc, v.get("v_round"), c["j_round"]))
            chk(group, "Spark heapify(Comet bytes).toUpdatableByteArray == Spark bytes",
                v.get("v_upd_same") == "1", cid, desc)
        if s == "A":
            chk(group, "Comet reads Spark bytes: same estimate",
                r.get("x_round") == c["j_round"] and r.get("x_bits") == jb, cid,
                "%s comet_on_spark=%s java=%s" % (desc, r.get("x_round"), c["j_round"]))
            chk(group, "Comet re-serialize(Spark bytes) == Java compact",
                bool(same_file(os.path.join(out, "rust", cid + ".xreser.bin"),
                               os.path.join(out, "java", cid + ".cmp.bin"))), cid, desc)
        if s == "B":
            ser = os.path.join(out, "rust", cid + ".xreser.bin")
            jc = os.path.join(out, "pool", c["pool"] + ".cmp.bin")
            chk(group, "Comet re-serialize == Java compact", bool(same_file(ser, jc)), cid, desc)
            chk(group, "Spark reads Comet re-serialized: same estimate",
                v.get("vx_round") == c["j_round"] and v.get("vx_bits") == jb, cid, desc)

    total_fail = sum(1 for _ in bad)
    print("## %s\n" % label)
    print("cases: %d, failed checks: %d\n" % (len(cases), total_fail))
    print("| group | cases | max rel diff | checks |")
    print("|---|---|---|---|")
    for g in sorted(stats):
        st = stats[g]
        checks = "<br>".join("%s: %d" % (k, n) for k, n in sorted(st.items()) if k != "cases")
        print("| %s | %d | %.3g | %s |" % (g, st["cases"], maxrel.get(g, 0.0), checks))
    print()
    if bad:
        by = collections.Counter((g, n) for g, n, _, _ in bad)
        print("### Failures by (group, check)\n")
        for (g, n), cnt in sorted(by.items()):
            print("- %s / %s: %d" % (g, n, cnt))
        print("\n### First 60 failures\n")
        for g, n, cid, d in bad[:60]:
            print("- `%s` %s / %s: %s" % (cid, g, n, d[:400]))
    print()


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
