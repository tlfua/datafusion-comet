---
name: ryan-take-issues
description: 幫 Ryan（tlfua）在 apache/datafusion-comet 對 user 指定的 issue 留 `take`，並確認真的被 assign。先檢查 issue 沒人在做，一次只 take 一個，等 assign 確認後才做下一個；失敗就刪掉沒生效的留言再重留。適合在 `ryan-search-issues` 推薦完、Ryan 選好 issue 之後使用。
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

# ryan-take-issues

寫作規則：用中文（台灣）描述，關鍵字和術語保留英文（例如 row、col、issue、PR、label、commit）。

## 連結規則（最高優先）

連結一律照 `ryan-update-pr-steps` 的「連結一定要是合法、點得開的 hyperlink」。重點：

- 給 user 看的輸出一律寫完整 URL，例如 https://github.com/apache/datafusion-comet/issues/7246 。URL 單獨一行，或前後留空白再接中文，不要黏上中文標點或括號。
- 不要寫裸的 `#N`。
- 不要自己拼 URL：issue 的 URL 從 `gh issue view --json url` 拿，`take` 留言的 URL 從建立留言時 `gh api` 回傳的 `html_url` 拿。

## 什麼時候用

user 已經選好要做的 issue，要正式 take 時使用。最常見的是 `ryan-search-issues` 推薦完之後。

## 動作邊界

- **`take` 是公開動作，只對 user 明確指定的 issue 做。** 不從推薦清單自己挑，也不順手 take 相關的 issue。
- 除了留 `take`，以及刪掉自己沒生效的 `take` 留言之外，不留其他留言、不改 label、不開 PR，也不建 worktree 或 branch。

## take 的機制

依據 `.github/workflows/take.yml`：

- 只在 issue comment **被建立**時觸發，編輯舊留言不會觸發。在 PR 上留 `take` 也不會觸發。
- 留言 body 必須**完全等於** `take` 或 `untake`，workflow 不會 trim。所以留言內容只能是 `take` 這四個字元，前後不能有空白、換行或其他字。不符合時 run 會是 `skipped`。
- workflow 用 `GITHUB_TOKEN` 把留言者 assign 上去。Ryan 沒有 push 權限，`gh issue edit --add-assignee` 會回 403，所以只能靠 `take`。
- workflow 會先確認留言者能不能被 assign，不能的話只印 `Cannot assign issue ...`，run 仍然是 `success`。所以**成功與否一律以 issue 的 assignees 為準**，不要只看 run 的結果。
- workflow 設了 `concurrency: group: ${{ github.actor }}-issue-assign`。同一個人連續留多個 `take` 時，還在排隊（pending）的 run 會被較新的 run 取代、變成 `cancelled`，只有部分 issue 會被 assign。已經在跑的 run 不會被取消。實際發生過：一次 take 10 個，只有 2 個成功。**所以一次只能 take 一個**，確認 assign 之後才留下一個。

## 流程

對 user 指定的每個 issue，依序做下面的步驟。一個 issue 做完（成功、跳過或失敗）才換下一個。

### 1. take 之前的檢查

任何一項不過，就跳過這個 issue，告訴 user 原因，不要 take。

- **issue 是 open，而且沒有 assignee**：

  ```sh
  gh issue view <N> -R apache/datafusion-comet --json state,assignees,url
  ```

  assignee 已經是 tlfua 的話，不用再 take，直接回報「已經是你的」。

- **沒有別人還有效的 `take`**：body 完全等於 `take` 或 `untake` 才算，依時間看每個 author 的最後一個狀態，最後是 `take` 就算還有效：

  ```sh
  gh api --paginate --slurp repos/apache/datafusion-comet/issues/<N>/comments \
    | jq -r 'add | map(select(.body=="take" or .body=="untake"))
             | group_by(.user.login) | map(max_by(.created_at))
             | map(select(.body=="take" and .user.login!="tlfua"))
             | .[] | "\(.user.login) \(.html_url)"'
  ```

  有輸出就停下來。指派失敗的 `take` 也算有人要做，因為外部 contributor 不一定能被 assign。

  tlfua 自己有還有效的 `take`、但沒有被 assign（之前沒生效的留言），不要直接再留一個：先告訴 user，經同意後照第 4 步刪掉舊的再重留。

- **沒有別人在留言說要做**：讀完所有留言，例如「I'll work on this」或「I'm working on it」。
- **沒有 open PR 在修它**：照 `ryan-search-issues` 的「non-take：沒有人在做」那一節，用 GraphQL 抓 timeline 的 cross-reference。只有 PR 提到這個 issue 還不夠，要打開那個 PR，確認它真的在修這個 issue。

### 2. 留 `take`

用 `gh api` 建立留言，同時拿到留言的 id、`created_at` 和 URL（第 3、4 步會用到）：

```sh
gh api repos/apache/datafusion-comet/issues/<N>/comments -f body=take --jq '"\(.id) \(.created_at) \(.html_url)"'
```

body 只能是 `take`。不要用 `gh issue comment` 的互動模式或 editor，避免多出換行。

### 3. 確認 assign

每 5 秒查一次 assignees，最多約 2 分鐘（24 次）。查到空的不代表失敗，run 可能還沒跑完，要等滿 2 分鐘才算等不到：

```sh
for i in $(seq 24); do
  a=$(gh api repos/apache/datafusion-comet/issues/<N> --jq '[.assignees[].login] | join(",")')
  case ",$a," in *,tlfua,*) echo "assigned: $a"; break ;; esac
  sleep 5
done
```

看到 tlfua 就算成功，才能進行下一個 issue。

等不到的話，看 workflow run 輔助判斷原因。tlfua 的其他留言也會產生 run，所以只看 `createdAt` 不早於這個 `take` 留言 `created_at` 的 run：

```sh
gh run list -R apache/datafusion-comet --workflow take.yml --user tlfua --limit 10 --json databaseId,status,conclusion,createdAt,url \
  | jq '[.[] | select(.createdAt >= "<take 留言的 created_at>")]'
```

- 還是 `queued` 或 `in_progress`：再等一下，不要重留。
- `cancelled`：被 concurrency 取代了。照「一次一個」的流程應該很少見，代表同時有其他 tlfua 的 `take`。照第 4 步補救。
- `skipped`：先用 `gh api repos/apache/datafusion-comet/issues/comments/<id> --jq .body` 確認 body 是不是剛好 `take`。不是的話照第 4 步補救。
- `success` 但沒有被 assign：讀 run 的 log（`gh run view <databaseId> -R apache/datafusion-comet --log`）。出現 `Cannot assign` 的話，重留也沒用：停下來告訴 user，並問要不要刪掉這個沒生效的 `take`，否則它會讓別人以為這個 issue 有人在做。
- 找不到對應的 run：可能是 GitHub 延遲，再等 1 分鐘再查一次，還是沒有就照第 4 步補救。

### 4. 失敗時的補救

workflow 只對新建立的留言觸發，編輯舊留言沒有用。

1. 先刪掉沒生效的那個 `take` 留言，避免 issue 上留著重複的 `take`：

   ```sh
   gh api -X DELETE repos/apache/datafusion-comet/issues/comments/<id>
   ```

2. 回到第 2 步重新留一個，一樣照第 3 步等 assign 確認。
3. 重試 1 次還是失敗，就停下來告訴 user，附上 run 的 URL，不要一直重留。

## 環境注意

- macOS 的 bash 是 3.2，沒有 `declare -A`。要把 issue 編號和留言 id 配對時，用 `n:c` 這種字串配對，例如：

  ```sh
  pairs="7246:6596318715 7300:6601234567"
  for p in $pairs; do n=${p%%:*}; c=${p#*:}; echo "issue $n comment $c"; done
  ```

- `gh api --paginate --slurp` 不能和 `--jq` 一起用，所以要 pipe 給 `jq`。

## 輸出

每個 issue 列出：

- issue 的完整 URL
- `take` 留言的完整 URL（有刪掉重留的話，列最後生效的那一個，並寫明刪過哪一個）
- 最後確認的 assignees

有失敗或被跳過的，寫明原因，例如「已經有人 take： <留言 URL> 」、「有 open PR： <PR URL> 」或「重試後仍未 assign： <run URL> 」。
