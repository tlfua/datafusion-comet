---
name: ryan-search-issues
description: 幫 Ryan 從 Comet 的「Bug triage results」總結 issue 裡，挑出適合他自己開 PR 的 issue。條件：沒人 take、不被別人的 PR 卡住、難度 2–4、跟他既有 PR 不同面向又有趣，預設挑 3 個。只搜尋和推薦，不留言、不 take。
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

# ryan-search-issues

寫作規則：用中文（台灣）描述，關鍵字和術語保留英文（例如 row、col、diff、commit、PR、CI、issue、label）。

## 連結規則（最高優先）

連結一律照 `ryan-update-pr-steps` 的「連結一定要是合法、點得開的 hyperlink」。重點：

- 給 user 看的輸出一律寫完整 URL，例如 `https://github.com/apache/datafusion-comet/issues/7403`。URL 單獨一行，或後面接空白，不要黏上中文標點或括號。
- 不要寫裸的 `#N`。
- 不要自己拼 URL，從 `gh` 的 `url` 或 `html_url` 拿；總結 issue 本文裡已經有完整 URL 的，也要再用 `gh issue view` 拿 `url` 核實。
- 每個 link 輸出前都用 `gh issue view`、`gh pr view` 或 `gh api` 確認存在。

## 什麼時候用

user 想找下一個自己開 PR 的 issue 時使用。

user 可以另外指定：數量、難度、topic 或 area。有指定就照 user 的，沒指定就用下面的預設值。

## 動作邊界

- **只搜尋和推薦。** 不在 issue 留 `take`，不留言、不改 label，也不開 PR。這些都是公開動作，由 user 自己決定。
- 這是 read-only 工作，不需要編譯，也不改 code。

## 篩選條件

### 1. 來源：只看「Bug triage results」總結 issue

- 候選來源只有標題是 `Bug triage results: <日期>`、還掛著 `requires-triage` label、狀態是 open 的總結 issue。例如：
  https://github.com/apache/datafusion-comet/issues/7403
- 這種 issue 是 `bug-triage` skill 產生的。它們本身掛著 `requires-triage`，裡面列出的 issue 通常已經被拿掉 `requires-triage`，並加上了 type、priority、area labels；但不保證，之後也可能被改或被關，要再確認。
- 找總結 issue：

  ```
  gh issue list -R apache/datafusion-comet --state open --label requires-triage --search 'in:title "Bug triage results"' --json number,title,url
  ```

- 候選 issue 是這些總結 issue 本文裡列出的 issue：
  - 用 `gh issue view <N> -R apache/datafusion-comet --json body -q .body` 拿完整本文，不要看被截斷的顯示。
  - 每個 bullet 只取結尾 `([#N](https://github.com/apache/datafusion-comet/issues/N))` 那個 link；標題或 rationale 裡順帶提到的 `#N` 不算候選。
  - 記下它在總結 issue 裡的 bug／enhancement 分類、priority、area labels 和 rationale。
  - 多個總結 issue 合併後用 issue 編號去重；同一個 issue 以最新的總結 issue 的資料為準。`Escalations to consider` 這類區段重複列出的也要去重。
- 排除：
  - 標題是 `Bug triage results` 的總結 issue 本身。
  - `[EPIC]` 這類 tracking issue，以及 research、audit 這類沒有明確修改目標的 issue。
  - `Skipped — needs more info` 區段的 issue，除非它現在已經有 type 和 priority label。
- 第一輪用便宜的查詢過濾：每個候選跑 `gh issue view <N> -R apache/datafusion-comet --json state,assignees,labels,title,url`，排除已經 closed、沒有 `bug` 或 `enhancement` label 的。候選很多時，可以把多個 issue 用 GraphQL alias 合成一個 query，避免 rate limit。
- 沒有 open 的總結 issue 時，停下來跟 user 說，不要改用其他來源。

### 2. non-take：沒有人在做

依據 https://github.com/apache/datafusion-comet/blob/main/docs/source/contributor-guide/contributing.md ：在 issue 留言 `take` 會被指派，留 `untake` 取消，由 `.github/workflows/take.yml` 自動處理。

下面任何一項成立，就排除：

- issue 有 assignee。
- 有人留過 `take`，而且之後沒有同一人的 `untake`。判斷方式跟 workflow 一樣用精確比對：用 `gh issue view <N> --json comments` 拿全部留言，body trim 後等於 `take` 或 `untake` 才算，依時間排序，每個 author 看最後一個狀態。
  - 指派失敗的 `take` 也算有人要做。
  - 外部 contributor 不一定能被指派，所以不能只看 assignee。
- 有人在留言裡說要做，例如「I'll work on this」或「I'm working on it」。
- 有 open PR 連到這個 issue：
  - 用 GraphQL 抓 timeline，PR 要用 inline fragment 才拿得到內容：

    ```
    gh api graphql -f query='{repository(owner:"apache",name:"datafusion-comet"){issue(number:N){timelineItems(first:100,itemTypes:[CROSS_REFERENCED_EVENT,CONNECTED_EVENT]){nodes{__typename ... on CrossReferencedEvent{source{... on PullRequest{url state merged isDraft author{login}}}} ... on ConnectedEvent{subject{... on PullRequest{url state}}}}}}}}'
    ```

  - 輔助：`gh pr list -R apache/datafusion-comet --state open --search "<N> in:body"`，結果只當線索。
  - cross-reference 只代表有 PR 提到這個 issue，要打開那個 PR 讀，確認它真的是在修這個 issue，才排除。
  - 有已經 merged 的 PR 真的修好了，也要排除。

### 3. 依賴最小：不用等別人的 PR

- 先用條件 1、2、4、5 和 priority 排出 shortlist（約 8–10 個），再對 shortlist 做完整的 link 閱讀：issue 本文、所有留言，以及裡面提到的每個 issue、PR、commit link；被提到的 issue 或 PR 也要點進去讀，看它的狀態和內容。這是「讀所有 link」在實務上的做法，最後推薦的每個 issue 一定要完整讀過。
- 排除以下情況：
  - 修法需要先等某個 open PR merge。
  - issue 明說 blocked by 或 depends on 其他未完成的工作。
  - 屬於某個進行中 EPIC 的後續步驟，而前一步還沒 merge。
- 會扣分但不一定排除：
  - 跟 open PR 改同一個檔案或同一塊 code，容易 conflict。
  - 要等 upstream 的 DataFusion、arrow-rs 或 iceberg-rust 先修或先 release。
  - 用 `gh pr list --search` 搜相關檔名或函式名，看有沒有 open PR 在碰同一塊。
- 輸出時每個推薦都要寫明依賴情況，例如「沒有依賴」，或「跟 <PR 完整 URL> 改同一檔，但不衝突，原因是…」。

### 4. 難度：預設挑 2–4

1 最簡單，5 最難。除非 user 另外指定，盡量挑 2–4。

估難度的依據要寫出來：

- 改動範圍：只改一個函式，還是跨好幾個模組。
- 要碰幾層：Rust、Scala serde、proto、shim、docs。
- 有沒有 Spark 版本差異要處理：3.4、3.5、4.0、4.1。
- 要不要新的測試基礎設施，或要重現 crash、記憶體或效能問題。
- 過往經驗：
  - 搜 repo 裡類似的 merged PR（同一 area、同類型 bug），看從開到 merge 花了多久、review 了幾輪、diff 多大。
  - 參考 user 自己過去 PR 的經驗。

### 5. 不同面向又有趣

- 先撈 user 自己的 PR，整理出已經碰過的 area、模組和主題：

  ```
  gh pr list -R apache/datafusion-comet --author tlfua --state all --limit 100 --json number,title,url,labels,state,isDraft
  ```

  先用 title 和 labels 判斷 area，看不出來的少數 PR 再用 `gh pr view <N> --json files` 補。例如 expressions 的 lpad／rpad、ANSI skipped-operand、TopK、HLL，以及 Iceberg writer。

- 優先挑 user 還沒碰過的 area，例如 scan、shuffle、memory、aggregation、writer 的其他部分，這樣才能廣泛吸收 Comet 的知識。除非 user 另外指定 topic。
- 有趣的定義：
  - 能學到 Comet 或 Spark 的某個核心機制，例如 Arrow FFI、shuffle 格式、memory pool、Spark 語意的邊角。
  - 修好之後對使用者有明顯價值，例如 crash 或 wrong result 這類高 priority bug。
- 每個推薦都要用一兩句說明有趣在哪、能學到什麼。

### 6. 數量：預設 3 個

除非 user 另外指定數量。每個推薦的 issue 都要附合法的 issue link：完整 URL 從 `gh issue view <N> -R apache/datafusion-comet --json url` 拿，輸出前確認 issue 存在且還是 open。符合條件的不夠時，照實說，不要硬湊；可以列出差一點符合的，並寫明差在哪。

## 其他檢查

- 用上面同一份 PR 清單（`state` 是 `OPEN` 且 `isDraft` 是 false）算出 user 目前 open 的 non-draft PR 數量。development guide 限制一次最多 3 個 open 的 non-draft PR，已經到上限時要提醒 user。
- 確認候選 issue 在 `main` 上還沒被修好。
  - 讀相關的 code。
  - 看相關檔案最近的 commit。
  - 確認測試沒有已經覆蓋這個情況。
- 開始前跑 `gh auth status`；GraphQL 查詢多時注意 rate limit（`gh api rate_limit`）。
- 搜尋量大時，可以把「讀候選 issue 和它提到的所有 link」交給 explore agent 平行處理。結論和 link 要自己核實。

## 輸出格式

先列一行摘要：

- 用了哪幾個總結 issue，附完整 URL。
- 候選共幾個，每個條件各排除了幾個。

接著每個推薦 issue 寫一段：

1. 標題和完整 URL。
2. type、priority、area labels。
3. 難度（1–5）和估計依據，附參考的類似 PR 完整 URL。
4. 依賴情況：有沒有 open PR 在碰，相關 issue 和 PR 的狀態，各附完整 URL。
5. 跟 user 既有 PR 的差異，以及有趣、能學到什麼。
6. 大概的修法方向和會改到的檔案。只是初步判斷，要標明還沒驗證。

最後列出被排除但值得一提的候選，各附一句原因。輸出完等 user 決定要不要 `take`，不要自己留言。
