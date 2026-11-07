---
name: ryan-update-pr-steps
description: Ryan 的 Comet PR impl steps default template。開始實作一個 PR，或在進行中的 PR 處理一輪 review comments 時使用：先確認 session 裡已經討論清楚，再依照 template 產生 detailed steps，交給 user 確認後才動手。
---

<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# ryan-update-pr-steps

寫作規則：用中文（台灣）描述，關鍵字和術語保留英文（例如 row、col、diff、commit、push、PR、CI、benchmark、backport）。連結一律用完整 URL，不要寫裸的 `#N`（在 fork 裡會連到錯的地方）。

## 什麼時候用

- 開始實作一個新的 PR。
- 在進行中的 PR 處理一輪 review comments（一個 cycle）。

## 第 0 步：先確認，不要沒頭沒腦直接 output

產生 detailed steps 之前，必須先往前看這個 session 的 logs，確認下面幾點都已經跟 user 討論過、有明確結論：

1. 要修的問題是什麼，scope 到哪裡（哪些 comment 要處理、哪些不碰）。
2. 修法已經定案，包括有爭議的設計選擇（例如白名單收哪些、門檻怎麼定）。
3. user 有沒有特別交代的限制（例如「不 merge upstream/main」、「這點我要親自處理」）。

任何一點不清楚，就停下來問 user，不要自己猜，也不要直接產生 steps。

## 固定規則（每次都適用，不用等 user 重複講）

### 每一步都要按照 comet repo guide

- 產生 steps 和執行每一步時，都要遵守 `AGENTS.md` 和 [Contributor Guide](../../../docs/source/contributor-guide/index.md)，包括 `development.md`、`ci.md`、`backporting.md`、`style_guide.md`，以及跟這次改動相關的 guide（例如 `adding_a_new_expression.md`）。
- 有對應的 repo skill 就用（`.ai/skills/` 底下），例如 review 用 `review-comet-pr`。
- 這份 SKILL 的規則跟 repo guide 衝突時，以 repo guide 為準，並告訴 user 哪裡衝突。

### 順序

- **review 在 build 之前**：`cargo fmt` → 自己看過整份 diff → rubber-duck review → 有問題就修 → 最後才做編譯和測試。review 中途改了 code，最後的檢查一定要重跑，不能沿用舊結果。
- **review 依據**：用 repo 的 `review-comet-pr` 加上對應領域的 skill（例如 `review-comet-expression-pr`）自己審查，有問題就修，修完再審，直到沒有問題。
- **review 範圍**：這個 PR 所有的 review points，不只這一輪的 comment，確保沒有把之前的修正改壞。但修改只照 reviewer 說的做，不要節外生枝。
- **push 前一定要檢查仔細**：fork CI 一輪約 70 分鐘（實際觀察值；`ci.md` 寫的是約 50–90 分鐘），跑錯就白等。
- **tests first**：新行為先補測試（SQL 測試、Rust 單元測試），確認修改前會失敗。
- 記得刪掉 dead src code 和 test code。

### 哪些在本機跑，哪些交給 fork CI

這台機器只有 8 GB 記憶體，重量級 build 會把機器拖垮。

| 項目 | 在哪跑 |
|---|---|
| `cargo fmt`、`cargo clippy -p <crate>`、`cargo test -p <crate>`、`cargo bench --no-run` | 本機，只編有改到的 crate（例如 `native/spark-expr`），`CARGO_BUILD_JOBS=4` |
| 有改 Scala 時：`make format`（會跑 Maven compile、scalafix、`spotless:apply`） | 本機，只有這種輕量 Maven goal |
| `./mvnw install`、`CometSqlFileTestSuite`、整套 JVM 測試、SQL 檔測試 | **不在本機跑**，交給 fork CI |
| 整個 native workspace 的 Rust 測試、TPC-H/TPC-DS 結果驗證 | fork CI |
| benchmark（需要穩定數字或比較多種策略） | fork 上另寫 workflow 跑 |
| 其他 Spark profile、Spark SQL suite、Iceberg 其他版本 | fork CI 只跑預設 Spark profile 和 lint；其他的要請維護者在 upstream PR 加 `run-*` label（見 `docs/source/contributor-guide/ci.md`） |

- Maven 一律從 repo root 跑，**絕對不要用 `-pl`**（見 `development.md` 的「Never Use `-pl` to Select Modules」）。
- 本機編譯之前，先檢查其他 session 有沒有正在編譯，有的話錯開，避免記憶體不足。

### fork CI 和 benchmark

- push 到 `origin`（tlfua/datafusion-comet）會自動觸發 fork CI，約 70 分鐘。
- 用背景 shell 每 2 分鐘查一次 CI，有 job 失敗或整個 run 結束就處理。失敗就讀 `gh run view <id> --log-failed`，修好後重做靜態檢查和 rubber-duck 再 push，最多連修 3 輪；如果是 CI 環境本身的問題，停下來回報。
- **自己加的 benchmark 或實驗用的 fork CI branch（例如 `bench/<issue>`）和 workflow 跑完不要刪**，PR update 裡要放 run 的 link 給 reviewer 參考。等 PR merge 後、user 說可以刪才刪。
- 實驗用的 workflow 和開關不要放進 PR，在 PR 說明裡寫清楚它們只是量測工具。
- 貼實驗結果（PR 說明、PR thread 或 issue 留言）時，要寫明 config、版本、資料等實驗設置，並附 run 的 link。

### Git 與 PR

- **不 merge 也不 rebase upstream/main**，除非 user 明確要求。repo guide 沒要求定期 sync main，只有衝突或 committer 要求時才做。
- 需要 force push 時用 `git push --force-with-lease`，被 reject 就先整合 remote 的變更，絕對不用 `--force`。
- 新 PR 先開 draft，fork CI 全過後才 publish。publish 前確認自己 open 的 non-draft PR 不超過 `development.md` 規定的上限（3 個）。
- PR 改到 benchmark 原始碼（例如 `native/spark-expr/benches/`）時，請維護者加 `run-benchmark-check` label。
- PR 說明照 `.github/pull_request_template.md` 填完每一段，並參考最近幾個一次就被 approve 的相關 PR 的寫法。
- 寫進 PR 或 issue 的每個說法和 link 都要先核實，不能只憑推測。
- 需要另開的 follow-up issue：先搜有沒有 existing issue，照 repo 的 issue template 開，再把完整 URL 附到 PR 或測試註解。
- **reviewer 要求（或建議）另開 follow-up issue 時**：
  - 這個 PR 的 scope 維持不變，不要順手把 follow-up 一起修進來。
  - 在 steps 裡列成獨立一步，寫明是哪個 reviewer 的哪個 comment（附 comment 的完整 URL）。
  - 在 apache/datafusion-comet 開 issue（不是 fork）：先搜 existing issue，有的話直接引用，不要重複開；沒有就照 `.github/ISSUE_TEMPLATE/` 選 bug 或 feature template，附可重現的 SQL、預期和實際結果，並連回這個 PR 和 reviewer 的 comment。只在 kernel 層級重現、沒實際跑過 SQL 的話，要照實寫出來。
  - 在 reviewer 那個 comment 的 thread 裡回覆 issue 的完整 URL。如果測試或 code 裡留著已知的缺口，就在註解附上 issue 的 URL。
  - PR 說明如果有「Out of scope / follow-up」之類的段落，也要附上 issue 的 link。
- fix 要進 release branch 時：照 `backporting.md` 用 `git cherry-pick -x` 開 backport PR，而且每個更新的 release branch 都要 backport。在 PR merge 之前，Ryan 的習慣是先開一個 backport tracking issue 記錄要 backport 的 branch（這是個人習慣，不是 repo guide 的要求）。

### 執行方式

- 先只 output steps（reply only），等 user 確認或修改後才動手。user 說「plz do it E2E」才開始做。
- 做的時候用 session todo 追蹤每一步，每 10 分鐘自動檢查一次進度，確保工作有效率。
- 結束時 worktree 要乾淨，最後給 user 看 updated PR（和 issue）的 link。

## Default template

依照這份 template 產生 detailed steps。用不到的步驟可以拿掉，但順序不要變。每一步要寫具體到檔案、函式、測試名稱、指令。

0. **準備**：確認沒有其他 session 在編譯；確認目前 branch 和 commit 是乾淨的（寫出 commit hash）；寫明這輪是否 merge upstream/main（預設不 merge）。
1. **Code 修改**：每個 review point 或子問題各一步，寫明誰提的、改哪個檔案、怎麼改、要補的測試。新行為先寫測試，確認修改前會失敗（Rust 單元測試在本機跑；SQL 測試只能靠 fork CI，就先在 review 時確認測試能抓到原本的問題）。
2. **Rust 單元測試和 benchmark 程式碼**（有需要時）：要補的 case 和 benchmark 形狀。
3. **SQL 測試**：要加的查詢、資料表，以及預期是 native 還是 fallback。
4. **`cargo fmt`（有改 Scala 就 `make format`）後做 review**：先自己看過整份 diff，再交給 rubber-duck，範圍是這個 PR 所有的 review points；發現問題就修；同時清掉 dead code。
5. **本機靜態檢查**：只編有改到的 crate，跑 clippy、單元測試、`cargo bench --no-run`。不跑 `./mvnw install` 和整套 JVM 測試，Maven 不用 `-pl`。
6. **fork 實驗**（有需要時）：例如在 `bench/<issue>` 跑 benchmark，用數據做決定；結果改變 code 的話回到第 4 步。跑完不要刪。
7. **Commit 並 push**：等 fork CI（約 70 分鐘，每 2 分鐘檢查一次），失敗就修，修完重做第 4、5 步再 push。
8. **Follow-up issue / backport tracking issue**（有需要時，包括 reviewer 要求另開的 issue）：開好後在對應的 review thread 回覆 issue 的完整 URL。
9. **更新 PR 並回覆 reviewer**：逐一回覆每個 comment，附上 commit 和 CI/benchmark 的 link；更新 PR 說明；需要時請維護者加 `run-*` 或 `run-benchmark-check` label；新 PR 從 draft 轉成 ready（先確認 non-draft PR 數量沒超過上限）。
10. **收尾**：把 session todo 標成完成，確認 worktree 乾淨，給 user 看 updated PR 的 link。

## 範例

apache/datafusion-comet#7246（ANSI short-circuit，處理 rich7420 和 coderfender 的 comments）這一輪產生的 detailed steps。這裡把 template 的第 1 步拆成兩步，所以編號比 template 多一號，例如「回到第 5 步」對應 template 的第 4 步：

0. 準備：確認沒有其他編譯在跑，分支停在乾淨的 `dc1559ce8`。全程不 merge upstream/main。
1. rich7420 的修正：`is_deterministic` 直接回傳 `is_deterministic(n.inner())`，加巢狀測試。
2. 白名單 `is_cheap`：初始只收 col、常數、整數算術、不需要解析的 `Cast`、`NullShortCircuit`（看它包住的運算式）。條件改成「便宜就一律先算全部 row，否則才套 50% 門檻」，加兩個單元測試。
3. Benchmark 程式碼：補 `nulls_0`，新增 LEGACY TPC-H Q1、`concat(s, upper(t))` 和各邊界類型的形狀。
4. SQL 測試：`try_divide` 對 `zero_bad`（含 decimal 版本），加一張 NULL row 超過一半的表。
5. `cargo fmt` 後做 review：先自己看過整份 diff，再交給 rubber-duck，發現問題就修。
6. 本機靜態檢查：只編 `native/spark-expr`，跑 clippy、測試、`cargo bench --no-run`。
7. 在 fork 跑 benchmark（`bench/7246`，約 1 小時），用數據決定邊界類型要不要進白名單；有改就回到第 5 步。
8. Commit 並 push，等 fork CI 約 70 分鐘；`try_divide` 若失敗就修。
9. 開 `branch-1.2` 的 backport tracking issue，連結一律用完整 URL。
10. 回覆 reviewer：回 rich7420 和 coderfender，更新 PR 說明，再請維護者加 CI label。
11. 把 session todo 標成完成。
