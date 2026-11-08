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

寫作規則：用中文（台灣）描述，關鍵字和術語保留英文（例如 row、col、diff、commit、push、PR、CI、benchmark、backport）。連結的規則見下一節。

## 連結一定要是合法、點得開的 hyperlink（user 一再提醒，最高優先）

不管是給 user 看的 steps、回覆，還是寫進 PR、issue、commit message 的內容，都要遵守：

- **一律寫完整 URL**，例如 `https://github.com/apache/datafusion-comet/pull/7246`。不要寫裸的 `#N`、`PR 7246`、`issue 123`、只有 commit hash 或檔名這類點不開的寫法（裸的 `#N` 在 fork 裡會連到錯的地方）。只有寫進 GitHub 會 render 的內容（PR、issue、comment 本文）才可以用 `apache/datafusion-comet#7246` 這種簡寫；給 user 看的終端機輸出一律完整 URL，因為終端機不會把簡寫轉成 link。
- **不要自己拼 URL**。comment、review、CI run、commit 的 URL 要從 `gh` 的輸出拿（例如 `html_url`、`url` 欄位，或 `gh run list --json url`），不能用猜的 id 或 anchor 自己組。
- **upstream 和 fork 不要搞混**：PR、issue、reviewer comment 在 `apache/datafusion-comet`；fork CI run、`bench/<issue>` branch、還沒進 upstream 的 commit 在 `tlfua/datafusion-comet`。commit link 要指到這個 commit 真的存在、而且已經 push 上去的 repo。
- **commit**：一律用完整 URL `https://github.com/<owner>/<repo>/commit/<完整 40 字元 SHA>`，不要用短 SHA。commit message 不是 Markdown，只能放純 URL，不要用 `[文字](URL)`。
- **檔案或行號**：用帶完整 commit SHA 的 permalink（`blob/<SHA>/path#L10-L20`），不要用 branch 名稱；SHA 用 `git rev-parse` 取得，而且要已經 push 到該 repo。
- **fork 的 benchmark 結果**：優先用 run URL 或帶 SHA 的 link，不要只放 branch 名稱。
- **輸出前逐一核實**：GitHub 的 link 用 `gh api`、`gh pr view`、`gh run view` 確認存在。`#discussion_r…`、`#issuecomment-…`、`#pullrequestreview-…` 這類 anchor，`curl` 驗不出來（anchor 不會送到 server），一律以 `gh api` 回傳的 `html_url` 為準（例如 `gh api repos/apache/datafusion-comet/pulls/comments/<id> --jq .html_url`；一般 comment 用 `issues/comments/<id>`，review 用 `pulls/<N>/reviews/<id>`）。`curl -sIL -o /dev/null -w '%{http_code}'` 只拿來驗非 GitHub 的公開網頁；回 404 或 429 可能是 private repo 或 rate limit，不能直接當成結論。核實不了的 link 不要放，跟 user 說明。
- **終端機輸出**：URL 單獨一行，或後面接空白再接中文；前後不要黏上中文標點或括號。不要用 `[文字](URL)`，很多終端機不會 render；`[文字](URL)` 只用在寫進 GitHub 的內容。

## 什麼時候用

- 開始實作一個新的 PR。
- 在進行中的 PR 處理一輪 review comments（一個 cycle）。

## 目標

**用最少的 review cycle 讓目前的 PR merge。** 每一步都以這個為準：一次把所有決定要處理的 comment 都處理到位，push 前檢查仔細，不要讓 reviewer 再為同一件事回來留言，也不要引入新的爭議點。

## 第 0 步：先確認，不要沒頭沒腦直接 output

產生 detailed steps 之前，必須先往前看這個 session 的 logs，確認下面幾點都已經跟 user 討論過、有明確結論：

1. 要修的問題是什麼，scope 到哪裡（哪些 comment 要處理、哪些不碰）。
2. 修法已經定案，包括有爭議的設計選擇（例如白名單收哪些、門檻怎麼定）。
3. user 有沒有特別交代的限制（例如「不 merge upstream/main」、「這點我要親自處理」）。

任何一點不清楚，就停下來問 user，不要自己猜，也不要直接產生 steps。

### reviewer 的 comments 互相衝突時

- 先把衝突點講清楚給 user：誰說了什麼（附 comment 的完整 URL），兩邊的理由，以及各自的擔心。
- 盡量找出一條大家都能接受的路，例如同時滿足兩邊核心擔心的設計、加開關或門檻、用 benchmark 數據說話，或是把有爭議的部分拆成 follow-up issue。
- 參考 repo 的歷史：用 `gh` 搜 apache/datafusion-comet 過去類似的 PR 或 issue 討論，看之前是怎麼解決的，例如由哪位 maintainer 拍板、用什麼數據說服對方，並附完整 URL 當依據。
- 提出建議方案和依據，等 user 決定後才寫進 steps。回覆 reviewer 時，要同時對兩邊說明為什麼這樣選。

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

| 項目                                                                                     | 在哪跑                                                                                                                              |
| ---------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `cargo fmt`、`cargo clippy -p <crate>`、`cargo test -p <crate>`、`cargo bench --no-run`  | 本機，只編有改到的 crate（例如 `native/spark-expr`），`CARGO_BUILD_JOBS=4`                                                          |
| 有改 Scala 時：`make format`（會跑 Maven compile、scalafix、`spotless:apply`）           | 本機，只有這種輕量 Maven goal                                                                                                       |
| 有改 `.md` 時：`npx prettier --check <file>`（CI Preflight 會檢查，不過就整個 run 失敗） | 本機                                                                                                                                |
| `./mvnw install`、`CometSqlFileTestSuite`、整套 JVM 測試、SQL 檔測試                     | **不在本機跑**，交給 fork CI                                                                                                        |
| 整個 native workspace 的 Rust 測試、TPC-H/TPC-DS 結果驗證                                | fork CI                                                                                                                             |
| benchmark（需要穩定數字或比較多種策略）                                                  | fork 上另寫 workflow 跑                                                                                                             |
| 其他 Spark profile、Spark SQL suite、Iceberg 其他版本                                    | fork CI 只跑預設 Spark profile 和 lint；其他的要請維護者在 upstream PR 加 `run-*` label（見 `docs/source/contributor-guide/ci.md`） |

- Maven 一律從 repo root 跑，**絕對不要用 `-pl`**（見 `development.md` 的「Never Use `-pl` to Select Modules」）。
- 本機編譯一律透過 build lock，開工前先檢查 disk，見下一節。

### 本機資源：disk 檢查和 build lock

**disk 檢查**（第 0 步做一次，第 6 步不重查）：

- 看剩餘空間（GB）：`df -g /Users/tien-lungfu/Desktop/RyanRepos | awk 'NR==2 {print $4}'`
- 門檻：這輪要在本機編譯（有 `cargo build`、`cargo test`、`cargo clippy`、`cargo bench` 或 `make format`），剩不到 **20 GB** 就 hard stop；只改 code、push 給 fork CI，剩不到 **5 GB** 就 hard stop。
- hard stop 時停下來通知 user，附上誰佔最多空間。兩個指令分開跑，因為 worktree 的總量已經包含它的 `native/target`，混在一起會重複計算：

  ```sh
  # 每個 worktree 的總量，加上 Maven 和 Rust 共用的 cache
  du -sh /Users/tien-lungfu/Desktop/RyanRepos/datafusion-comet* ~/.m2 ~/.cargo 2>/dev/null | sort -h
  # 其中各 worktree 的 Rust 編譯產物
  du -sh /Users/tien-lungfu/Desktop/RyanRepos/datafusion-comet*/native/target 2>/dev/null | sort -h
  ```

  只列出建議可以清的項目（例如已 merge 的 PR worktree 的 `native/target`），**不自動刪**，等 user 決定。

**build lock**：同一時刻只讓一個 Comet session 在本機編譯，避免 8 GB 記憶體不夠。

- `cargo build`、`cargo test`、`cargo clippy`、`cargo bench`、`make format`（會跑 `./mvnw compile`）一律透過 script 跑，例如：

  ```sh
  /Users/tien-lungfu/Desktop/RyanRepos/datafusion-comet-ryan-skills/.ai/skills/ryan-update-pr-steps/with-build-lock.sh \
    cargo test -p datafusion-comet-spark-expr
  ```

  單獨跑 `cargo fmt`、`git`、`gh`，或等 fork CI，都不用拿 lock。

- 一定要寫絕對路徑，因為 working session 的 cwd 是它自己的 worktree。`datafusion-comet-ryan-skills` 這個 worktree 要一直停在有這支 script 的 branch 上。
- 拿 lock、編譯、釋放都在同一個 bash call 裡完成（script 會自動處理），不要分成多個 call，也不要自己 `mkdir` 或刪 lock。
- **配合 bash tool 的用法**：等 lock 最多 30 分鐘，再加上編譯時間，會超過 sync 的 `initial_wait`。
  - 用 async mode 跑，等完成通知後再用 `read_bash` 讀結果。
  - 前一個 call 還沒結束時，不要重跑同一個指令，否則同一個 session 會排在自己後面等 lock。
  - 要中途停掉就用 `stop_bash`。實測過：script、編譯指令和它底下的子程序都會停掉，lock 也會釋放。
- script 的行為：
  - lock 是 `/Users/tien-lungfu/Desktop/RyanRepos/.comet-build.lock/`，用 `mkdir` 建立（atomic）。`owner` 檔記錄 script 的 PID、process 啟動時間，以及 cwd 和指令。`pgid` 檔記錄編譯指令的 process group，由指令自己在啟動時寫入，所以 script 在那一瞬間被 SIGKILL 也會留下記錄。
  - 編譯指令跑在自己的 process group 裡，stdin 是 `/dev/null`。
  - 拿不到就每 60 秒試一次，超過 30 分鐘還拿不到就 exit 75。這時停下來通知 user，附上 script 印出的 holder 資訊。
  - 編譯結束或失敗都會釋放 lock。收到 HUP、INT、TERM 時，先對整個 process group 送 TERM（包括 `cargo` 底下的 `rustc`、`mvnw` 底下的 `java`），10 秒後還沒停就送 KILL，然後才釋放 lock。用 `setsid` 之類方式脫離 process group 的程序管不到，`cargo` 和 `mvnw` 不會這樣做。
  - INT 只有在 script 從前景啟動時才有效：非互動式 bash 用 `&` 在背景啟動的程序，一開始就忽略 INT，bash 也沒辦法再 trap。HUP 和 TERM 不受影響。
  - script 被 SIGKILL 時，沒機會停掉編譯，也沒機會釋放 lock。只要那個 process group 裡還有 process 在跑，lock 就不算 stale，其他 session 會等它編完才拿 lock，不會同時編譯。代價是：如果 process group 裡留下長駐的 process（例如編譯時啟動的 daemon），lock 會一直被佔住，其他 session 等滿 30 分鐘就 exit 75。這時用 `ps -o pid,pgid,command -g "$(cat /Users/tien-lungfu/Desktop/RyanRepos/.comet-build.lock/pgid)"` 看是哪些 process，交給 user 決定要不要停掉。
  - 釋放前會確認 `owner` 還是自己；如果 lock 已經是別人的，就不會刪。
  - stale lock 會自動清，條件是 owner 的 process 已經不存在（或 PID 被別的 process 重複使用，啟動時間對不上），而且編譯指令的 process group 也沒有 process 了。沒有 `owner` 檔，或 owner 已經不在但沒有 `pgid` 檔時，要超過 60 秒才當成 stale，讓剛啟動的一方有時間寫入。清理時先拿第二層 lock `.comet-build.lock.reap`，避免兩個 session 互刪。
  - `ps` 讀不到啟動時間時一律當成 owner 還活著，寧可等也不搶 lock。拿 lock 時如果 `ps` 讀不到自己的啟動時間，就直接 exit 75，不拿 lock。
  - 拿 lock 和清理 stale lock 的途中收到 signal，會等這一小段做完才處理，不會留下沒有 `owner` 的 lock 或殘留的 reap lock。
  - `.comet-build.lock.reap` 留著超過 5 分鐘，代表之前的清理 crash 了。script 會 exit 75，停下來通知 user 手動處理。
- 補強：拿 lock 前仍用 `ps` 檢查有沒有不經 lock 的編譯在跑（例如手動跑的 `cargo`、`rustc`、`mvn`），有的話錯開。VS Code 的 rust-analyzer 管不到。
- **改 script 之後**一定要跑測試，全過才 commit：

  ```sh
  /Users/tien-lungfu/Desktop/RyanRepos/datafusion-comet-ryan-skills/.ai/skills/ryan-update-pr-steps/test-with-build-lock.sh
  ```

  測試用暫存的 lock 路徑、很短的 timeout，以及 `sh`、`sleep` 這類 mock 指令取代真正的編譯；`ps` 失敗和清理變慢的情況，則用放在 `PATH` 最前面的假 `ps`、`rm` 模擬。不會碰到真正的 lock，約 20 秒跑完。

  涵蓋：exit status、互斥、各種 stale lock、`ps` 失敗、timeout、殘留的 reap lock、lock 已經不是自己的、HUP/INT/TERM 中斷（包括指令的子程序）、忽略 TERM 的指令、script 被 SIGKILL、owner 死了但還沒有 `pgid`、清理途中被中斷。新增行為時要一起補測試。

  測試靠環境變數 `COMET_BUILD_LOCK`、`COMET_BUILD_LOCK_POLL_SECS`、`COMET_BUILD_LOCK_MAX_WAIT_SECS`、`COMET_BUILD_LOCK_OWNER_GRACE_SECS`、`COMET_BUILD_LOCK_REAP_STALE_SECS`、`COMET_BUILD_LOCK_KILL_GRACE_SECS` 覆蓋預設值，平常使用不要設。

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
- 寫進 PR 或 issue 的每個說法都要先核實，不能只憑推測；link 的規則見「連結一定要是合法、點得開的 hyperlink」。
- **Follow-up issue**（自己發現的，或 reviewer 要求、建議另開的）：
  - **先確認 reviewer 的意思**：是要另開 issue，還是要在這個 PR 修？不確定就問 user。reviewer 只是「建議」另開時，先問 user 要不要開，不要預設就開。
  - 確定另開的話，這個 PR 的 scope 維持不變，不要順手把 follow-up 一起修進來。
  - 在 steps 裡列成獨立一步，寫明是哪個 reviewer 的哪個 comment（附 comment 的完整 URL）。
  - 先搜 existing issue，有的話直接引用，不要重複開，這一步就改成「回覆既有 issue 的 URL，需要時在那個 issue 補留言」。
  - 沒有的話在 apache/datafusion-comet 開（不是 fork）：依內容是 bug 還是 enhancement，選 `.github/ISSUE_TEMPLATE/` 的 `bug_report.yml` 或 `feature_request.yml`。Ryan 的標準比 template 嚴格：要附可重現的 SQL、預期和實際結果，並連回這個 PR 和 reviewer 的 comment。只在 kernel 層級重現、沒實際跑過 SQL 的話，要照實寫出來。
  - **開 upstream issue 是公開動作**：標題和內文草稿要列在 steps 裡，等 user 確認後才建立。「Willingness to contribute」這類欄位要問 user，不要替 user 選。
  - 不要自己加或移除 label（例如 `requires-triage`），triage 由 maintainer 做。
  - 測試或 code 裡留著已知的缺口時，在註解附上 issue 的完整 URL；PR 說明如果有 follow-up 之類的段落，也附上 link。
- fix 要進 release branch 時：照 `backporting.md` 用 `git cherry-pick -x` 開 backport PR，而且每個更新的 release branch 都要 backport。在 PR merge 之前，Ryan 的習慣是先開一個 backport tracking issue 記錄要 backport 的 branch（這是個人習慣，不是 repo guide 的要求）。

### 執行方式

- 先只 output steps（reply only），等 user 確認或修改後才動手。user 說「plz do it E2E」才開始做。
- 做的時候用 session todo 追蹤每一步，每 10 分鐘自動檢查一次進度，確保工作有效率。
- 結束時 worktree 要乾淨，最後給 user 看 updated PR（和 issue）的 link。

## Default template

依照這份 template 產生 detailed steps。用不到的步驟可以拿掉，但順序不要變。每一步要寫具體到檔案、函式、測試名稱、指令。

0. **準備**：照「本機資源：disk 檢查和 build lock」檢查 disk（寫出剩餘 GB、這輪套用的門檻，以及這輪會不會在本機編譯），不夠就 hard stop；確認目前 branch 和 commit 是乾淨的（寫出 commit hash）；寫明這輪是否 merge upstream/main（預設不 merge）。
1. **Code 修改**：每個 review point 或子問題各一步，寫明誰提的、改哪個檔案、怎麼改、要補的測試。新行為先寫測試，確認修改前會失敗（Rust 單元測試在本機跑；SQL 測試只能靠 fork CI，就先在 review 時確認測試能抓到原本的問題）。
2. **Rust 單元測試和 benchmark 程式碼**（有需要時）：要補的 case 和 benchmark 形狀。
3. **SQL 測試**：要加的查詢、資料表，以及預期是 native 還是 fallback。
4. **`cargo fmt`（有改 Scala 就 `make format`）後做 review**：先自己看過整份 diff，再交給 rubber-duck，範圍是這個 PR 所有的 review points（清單以第 5 步抓下來的 comments 為準）；發現問題就修；同時清掉 dead code。
5. **Comment coverage verify**：當下往前看，用 `gh` 重新抓這個 PR 所有的 comments，不要只靠記憶：
   - inline review comments：`gh api --paginate repos/apache/datafusion-comet/pulls/<N>/comments`
   - PR 下方的一般 comments：`gh api --paginate repos/apache/datafusion-comet/issues/<N>/comments`
   - review 本文（approve / request changes 附的文字）：`gh api --paginate repos/apache/datafusion-comet/pulls/<N>/reviews`
   - thread 是否 resolved 以 GraphQL `reviewThreads { isResolved isOutdated }` 為準；`isOutdated` 的 thread 也要看是否仍適用。bot 留言只在有實質要求時列入。

   列一張表：每個 comment（附完整 URL）、處理方式（在這個 PR 修／另開 issue／不處理和原因）、對應的 diff 或測試。**處理方式只能沿用第 0 步和 user 已確認的結論**；steps 確認後才出現的新 comment，或沒被 user 決定過的 comment，停下來列給 user，等 user 決定，不要自己補修。已確認要處理卻漏掉的才補，衝突的 comments 也要照第 0 步的方案處理；有補就回到第 4 步。

6. **本機靜態檢查**：只編有改到的 crate，跑 clippy、單元測試、`cargo bench --no-run`，每個編譯指令都透過 `with-build-lock.sh`（絕對路徑）跑。不跑 `./mvnw install` 和整套 JVM 測試，Maven 不用 `-pl`。
7. **fork 實驗**（有需要時）：例如在 `bench/<issue>` 跑 benchmark，用數據做決定；結果改變 code 的話重做第 4–6 步，接著直接進第 8 步（只有效能行為又改變時才重跑實驗）。跑完不要刪。
8. **Commit 並 push**：等 fork CI（約 70 分鐘，每 2 分鐘檢查一次），失敗就修，修完重做第 4–6 步再 push。
9. **Follow-up issue / backport tracking issue**（有需要時，包括 reviewer 要求另開的 issue）：草稿經 user 確認後才建立。這一步只記下 issue 的完整 URL，不回覆 reviewer。
10. **更新 PR 並回覆 reviewer**：回覆前照第 5 步的做法再抓一次 comments，跟第 5 步的表比對（CI 跑的這段時間可能有新 comment）。有新增或被修改的 comment，先停下來給 user 看，需要改 code 就回到第 4 步，不要直接回覆。沒有差異才照表逐一回覆每個 comment，每個 comment 只回一次，附上 commit 和 CI/benchmark 的 link；要求另開 issue 的 comment，在同一則回覆裡附上 issue 的完整 URL。更新 PR 說明；需要時請維護者加 `run-*` 或 `run-benchmark-check` label；新 PR 從 draft 轉成 ready（先確認 non-draft PR 數量沒超過上限）。送出前照「連結一定要是合法、點得開的 hyperlink」那節逐一核實每個 link。
11. **收尾**：把 session todo 標成完成，確認 worktree 乾淨，給 user 看 updated PR 的完整 URL（核實過、點得開）。

## 範例

https://github.com/apache/datafusion-comet/pull/7246 （ANSI short-circuit，處理 rich7420 和 coderfender 的 comments）這一輪產生的 detailed steps。這個範例比 comment coverage verify 步驟早，所以沒有那一步；而且它把 template 的第 1 步拆成兩步，所以範例的編號跟 template 對不上。範例裡的「回到第 N 步」指的是範例自己的編號：

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
