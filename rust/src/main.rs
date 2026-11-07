//! Replays the case corpus written by `HllSpark gen` through Comet's own HLL code
//! (`SparkHllSketch`, `SparkHllUnion`, `spark_hll_union`, `spark_hll_sketch_estimate`).
//! The accumulator glue below copies `HllSketchAccumulator` / `HllUnionAccumulator`
//! from `native/spark-expr/src/agg_funcs/hll_{sketch,union}_agg.rs`, whose structs are private.

use datafusion::arrow::array::{Array, ArrayRef, BinaryArray, BooleanArray, Int64Array};
use datafusion::physical_plan::ColumnarValue;
use datafusion_comet_spark_expr::{
    spark_hll_sketch_estimate, spark_hll_union, SparkHllSketch, SparkHllUnion,
};
use std::collections::HashMap;
use std::fs;
use std::io::{BufRead, BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;

const GOLD: i64 = 0x9E37_79B9_7F4A_7C15u64 as i64;
const SPECIAL: [i64; 12] = [
    0,
    -1,
    1,
    i64::MIN,
    i64::MAX,
    i32::MIN as i64,
    i32::MAX as i64,
    255,
    256,
    -256,
    1i64 << 32,
    -(1i64 << 32),
];
const ALPHA: [u32; 21] = [
    'a' as u32, 'b' as u32, 'c' as u32, 'x' as u32, 'y' as u32, 'z' as u32, '0' as u32,
    '1' as u32, '9' as u32, ' ' as u32, '-' as u32, '_' as u32, 0xE9, 'e' as u32, 0x301,
    0x4E2D, 0x6587, 0xDF, 0x3042, 0x1F600, 0x1F680,
];

fn lv(seed: i64, i: i64) -> i64 {
    seed.wrapping_mul(1000003).wrapping_add(i).wrapping_mul(GOLD)
}

fn base36(mut i: i64) -> String {
    if i == 0 {
        return "0".to_string();
    }
    let mut v = Vec::new();
    while i > 0 {
        v.push(std::char::from_digit((i % 36) as u32, 36).unwrap());
        i /= 36;
    }
    v.iter().rev().collect()
}

fn sv(seed: i64, i: i64) -> String {
    if i % 97 == 13 {
        return String::new();
    }
    let len = 1 + ((i * 7 + seed) % 40);
    let mut s = String::new();
    for j in 0..len {
        let h = (i
            .wrapping_mul(31)
            .wrapping_add(j)
            .wrapping_mul(GOLD)
            .wrapping_add(seed) as u64)
            >> 33;
        s.push(char::from_u32(ALPHA[(h % ALPHA.len() as u64) as usize]).unwrap());
    }
    s.push_str(&base36(i));
    s
}

enum Val {
    L(i64),
    S(String),
}

fn value(kind: &str, seed: i64, i: i64) -> Val {
    match kind {
        "long" => Val::L(lv(seed, i)),
        // Spark widens IntegerType to long; Comet's update_ints does `.into()`.
        "int" => Val::L(lv(seed, i) as i32 as i64),
        "special" => Val::L(SPECIAL[(i % SPECIAL.len() as i64) as usize]),
        "str" => Val::S(sv(seed, i)),
        _ => panic!("kind {kind}"),
    }
}

fn feed(s: &mut SparkHllSketch, kind: &str, seed: i64, lo: i64, hi: i64) {
    for i in lo..hi {
        match value(kind, seed, i) {
            Val::L(v) => s.update_i64(v),
            Val::S(v) => s.update_bytes(v.as_bytes()),
        }
    }
}

fn mix(h: &mut u64, b: u8) {
    *h ^= b as u64;
    *h = h.wrapping_mul(0x100000001b3);
}

fn checksum(kind: &str, seed: i64, lo: i64, hi: i64) -> String {
    let mut h: u64 = 0xcbf29ce484222325;
    for i in lo..hi {
        match value(kind, seed, i) {
            Val::L(v) => v.to_le_bytes().iter().for_each(|b| mix(&mut h, *b)),
            Val::S(v) => {
                v.as_bytes().iter().for_each(|b| mix(&mut h, *b));
                mix(&mut h, 0xff);
            }
        }
    }
    format!("{h:x}")
}

fn order(n: usize, rev: bool) -> Vec<usize> {
    (0..n).map(|i| if rev { n - 1 - i } else { i }).collect()
}

/// Comet hll_sketch_agg after apache/datafusion-comet#7345: Partial accumulators per partition
/// (`HllSketchAccumulator::Sketch`), state = to_sketch_bytes. The Final accumulator starts as an
/// empty `Sketch`, and its first `merge_batch` (`union_mut`) turns it into
/// `SparkHllUnion::new(k)` with that empty sketch merged in. Every partial state is then merged
/// into that one union, and `evaluate` returns `union.to_sketch_bytes()`.
fn comet_sketch_agg(k: u8, kind: &str, seed: i64, start: i64, n: i64, p: i64, rev: bool) -> Vec<u8> {
    let mut partials = Vec::new();
    for q in 0..p {
        let lo = start + q * n / p;
        let hi = start + (q + 1) * n / p;
        let mut s = SparkHllSketch::new(k);
        feed(&mut s, kind, seed, lo, hi);
        partials.push(s.to_sketch_bytes());
    }
    let mut union = SparkHllUnion::new(k);
    union.merge(&SparkHllSketch::new(k));
    for idx in order(p as usize, rev) {
        union.merge(&SparkHllSketch::from_bytes(&partials[idx]).unwrap());
    }
    union.to_sketch_bytes()
}

fn comet_estimate(bytes: &[u8]) -> Result<i64, String> {
    let arr: ArrayRef = Arc::new(BinaryArray::from(vec![Some(bytes)]));
    match spark_hll_sketch_estimate(&[ColumnarValue::Array(arr)]) {
        Ok(ColumnarValue::Array(a)) => Ok(a.as_any().downcast_ref::<Int64Array>().unwrap().value(0)),
        Ok(_) => Err("scalar result".to_string()),
        Err(e) => Err(e.to_string()),
    }
}

fn comet_union(a: &[u8], b: &[u8], allow: bool) -> Result<Vec<u8>, String> {
    let aa: ArrayRef = Arc::new(BinaryArray::from(vec![Some(a)]));
    let bb: ArrayRef = Arc::new(BinaryArray::from(vec![Some(b)]));
    let al: ArrayRef = Arc::new(BooleanArray::from(vec![allow]));
    match spark_hll_union(&[
        ColumnarValue::Array(aa),
        ColumnarValue::Array(bb),
        ColumnarValue::Array(al),
    ]) {
        Ok(ColumnarValue::Array(r)) => Ok(r
            .as_any()
            .downcast_ref::<BinaryArray>()
            .unwrap()
            .value(0)
            .to_vec()),
        Ok(_) => Err("scalar result".to_string()),
        Err(e) => Err(e.to_string()),
    }
}

/// Copy of `HllUnionAccumulator`.
struct UnionAcc {
    union: Option<SparkHllUnion>,
    allow: bool,
    seen: Option<u8>,
}

impl UnionAcc {
    fn new(allow: bool) -> Self {
        Self { union: None, allow, seen: None }
    }

    fn absorb(&mut self, bytes: &[u8]) -> Result<(), String> {
        let sketch = SparkHllSketch::from_bytes(bytes).map_err(|e| e.to_string())?;
        let k = sketch.lg_config_k();
        match self.seen {
            None => {
                self.seen = Some(k);
                self.union = Some(SparkHllUnion::new(k));
            }
            Some(prev) if prev != k && !self.allow => {
                return Err(format!("Sketches have different lgConfigK values: {prev} and {k}"));
            }
            _ => {}
        }
        self.union.as_mut().unwrap().merge(&sketch);
        Ok(())
    }

    fn state(&self) -> Option<Vec<u8>> {
        self.union.as_ref().map(|u| u.to_sketch_bytes())
    }

    fn evaluate(&self) -> Vec<u8> {
        match &self.union {
            Some(u) => u.to_sketch_bytes(),
            None => SparkHllUnion::new(12).to_sketch_bytes(),
        }
    }
}

fn comet_union_agg(items: &[Vec<u8>], p: usize, rev: bool, allow: bool) -> Result<Vec<u8>, String> {
    let m = items.len();
    let mut states = Vec::new();
    for q in 0..p {
        let mut acc = UnionAcc::new(allow);
        for item in &items[q * m / p..(q + 1) * m / p] {
            acc.absorb(item)?;
        }
        states.push(acc.state());
    }
    let mut fin = UnionAcc::new(allow);
    for idx in order(p, rev) {
        if let Some(b) = &states[idx] {
            fin.absorb(b)?;
        }
    }
    Ok(fin.evaluate())
}

fn kv(line: &str) -> (String, HashMap<String, String>) {
    let mut it = line.split('\t');
    let id = it.next().unwrap().to_string();
    let m = it
        .map(|f| {
            let (k, v) = f.split_once('=').unwrap();
            (k.to_string(), v.to_string())
        })
        .collect();
    (id, m)
}

fn est_fields(prefix: &str, bytes: &[u8]) -> String {
    let round = match comet_estimate(bytes) {
        Ok(v) => v.to_string(),
        Err(e) => format!("ERR:{}", e.replace('\t', " ")),
    };
    match SparkHllSketch::from_bytes(bytes) {
        Ok(s) => {
            let e = s.estimate();
            format!(
                "\t{prefix}_est={e:?}\t{prefix}_bits={:x}\t{prefix}_round={round}",
                e.to_bits()
            )
        }
        Err(e) => format!("\t{prefix}_err={}", e.to_string().replace('\t', " ")),
    }
}

struct Pool {
    lgk: u8,
    n: i64,
    seed: i64,
    start: i64,
    maker: String,
    ck: String,
}

fn main() {
    let out = PathBuf::from(std::env::args().nth(1).expect("outDir"));
    fs::create_dir_all(out.join("rust")).unwrap();
    let read = |p: &Path| fs::read(p).unwrap_or_else(|e| panic!("{}: {e}", p.display()));

    let mut pool: HashMap<String, Pool> = HashMap::new();
    for line in BufReader::new(fs::File::open(out.join("pool.tsv")).unwrap()).lines() {
        let (id, m) = kv(&line.unwrap());
        pool.insert(
            id,
            Pool {
                lgk: m["lgk"].parse().unwrap(),
                n: m["n"].parse().unwrap(),
                seed: m["seed"].parse().unwrap(),
                start: m["start"].parse().unwrap(),
                maker: m["maker"].clone(),
                ck: m["ck"].clone(),
            },
        );
    }
    let pool_bytes = |pid: &str, form: &str| read(&out.join(format!("pool/{pid}.{form}.bin")));

    let mut w = BufWriter::new(fs::File::create(out.join("rust.tsv")).unwrap());
    let mut count = 0usize;
    for line in BufReader::new(fs::File::open(out.join("cases.tsv")).unwrap()).lines() {
        let (id, m) = kv(&line.unwrap());
        let mut row = id.clone();
        match m["set"].as_str() {
            "A" => {
                let k: u8 = m["lgk"].parse().unwrap();
                let kind = m["kind"].as_str();
                let seed: i64 = m["seed"].parse().unwrap();
                let n: i64 = m["n"].parse().unwrap();
                let p: i64 = m["P"].parse().unwrap();
                let rev = m["rev"] == "1";
                row += &format!("\tck_ok={}", (checksum(kind, seed, 0, n) == m["ck"]) as u8);
                let bytes = comet_sketch_agg(k, kind, seed, 0, n, p, rev);
                fs::write(out.join(format!("rust/{id}.bin")), &bytes).unwrap();
                row += &est_fields("r", &bytes);
                let jupd = read(&out.join(format!("java/{id}.upd.bin")));
                row += &est_fields("x", &jupd);
                let reser = SparkHllSketch::from_bytes(&jupd).unwrap().to_sketch_bytes();
                fs::write(out.join(format!("rust/{id}.xreser.bin")), &reser).unwrap();
            }
            "B" => {
                let b = pool_bytes(&m["pool"], &m["form"]);
                row += &est_fields("r", &b);
                if let Ok(s) = SparkHllSketch::from_bytes(&b) {
                    fs::write(out.join(format!("rust/{id}.xreser.bin")), s.to_sketch_bytes()).unwrap();
                }
            }
            "C" => {
                let a = pool_bytes(&m["a"], &m["af"]);
                let b = pool_bytes(&m["b"], &m["bf"]);
                match comet_union(&a, &b, m["allow"] == "1") {
                    Ok(bytes) => {
                        fs::write(out.join(format!("rust/{id}.bin")), &bytes).unwrap();
                        row += "\tr_err=0";
                        row += &est_fields("r", &bytes);
                    }
                    Err(e) => row += &format!("\tr_err=1\tr_msg={}", e.replace('\t', " ")),
                }
            }
            "D" => {
                let pids: Vec<&str> = if m["items"] == "-" {
                    vec![]
                } else {
                    m["items"].split(',').collect()
                };
                let items: Vec<Vec<u8>> = if m["src"] == "rust" {
                    pids.iter()
                        .map(|pid| {
                            let pl = &pool[*pid];
                            assert_eq!(pl.maker, "agg");
                            assert_eq!(checksum("long", pl.seed, pl.start, pl.start + pl.n), pl.ck);
                            comet_sketch_agg(pl.lgk, "long", pl.seed, pl.start, pl.n, 1, false)
                        })
                        .collect()
                } else {
                    pids.iter().map(|pid| pool_bytes(pid, "upd")).collect()
                };
                let p: usize = m["P"].parse().unwrap();
                match comet_union_agg(&items, p, m["rev"] == "1", m["allow"] == "1") {
                    Ok(bytes) => {
                        fs::write(out.join(format!("rust/{id}.bin")), &bytes).unwrap();
                        row += "\tr_err=0";
                        row += &est_fields("r", &bytes);
                    }
                    Err(e) => row += &format!("\tr_err=1\tr_msg={}", e.replace('\t', " ")),
                }
            }
            other => panic!("set {other}"),
        }
        writeln!(w, "{row}").unwrap();
        count += 1;
    }
    w.flush().unwrap();
    println!("rust cases={count}");
}
