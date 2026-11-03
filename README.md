Scratch experiment for https://github.com/apache/datafusion-comet/issues/7396 (not for merge).

- `java/HllSpark.java`: mirrors Spark 4.x `HllSketchAgg`, `HllUnionAgg`, `HllUnion`, `HllSketchEstimate`
  on DataSketches-Java 6.1.1 (Spark 4.0) and 6.2.0 (Spark 4.1/4.2), and writes the case corpus.
- `rust/`: replays the corpus through Comet's own HLL code at apache/datafusion-comet@644a870
  (datasketches 0.6.0).
- `summarize.py`: compares estimates (bit-exact, 1e-6, rounded), bytes, and cross-reads.
