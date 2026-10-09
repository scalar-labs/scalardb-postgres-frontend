# 限界値テスト 結果報告（postgres-frontend）

- 対象: `main` `071156e`（2026-10-09 の再実行。テスト側の cases.json と SqlRunner をそのまま使い、Native PG と frontend 経由・PG 格納の 2 列だけを取り直した。Core 直と MySQL 格納の列は 2026-10-08 の結果のまま。JVM の既定タイムゾーンは Asia/Tokyo だったため、Native と frontend の両方で I-TSZ-01/02 が +09 で出ている。UTC で取り直すと +00 になる。rerun-compare.txt が 2026-10-08 の報告との差分）
- テスト項目: **149 項目**（INSERT・SELECT・UPDATE/DELETE・DDL）＋ マルチストレージ 18 項目 ＋ 障害系 3 項目
- 計画: `../plan-boundary-tests-20261008.md`。ケースの定義: `cases/cases.py`、`cases/cases_multi.py`。生の結果: `results/*.json`

## 1. この文書の読み方

### 1.1 何を比べたか

1 つのテスト項目（例:「INT の最大値を INSERT する」）を、次の 5 通りの方法で実行し、結果を **期待値（PostgreSQL ならこうなる、という結果）** と比べた。

下の表は、結果の表に出てくる 5 つの列の意味。列の説明: 「列の名前」= 結果の表での列の見出し、「何を実行したか」= その列の結果を得た方法、「この列が示すこと」= その列から読み取れること。

| 列の名前 | 何を実行したか | この列が示すこと |
|---|---|---|
| Native PG（期待値の確認） | 本物の PostgreSQL 16 に SQL を流す | 期待値の書き方が正しいことの確認。**全項目で期待値と一致した**（ここが一致しないと、他の列の判定は信用できない） |
| Core 直・PG 格納 | frontend を通さず、ScalarDB の Java API で同じ操作をする。格納先は PostgreSQL | ScalarDB 本体の性質。ここで違えば、frontend を直しても直らない |
| Core 直・MySQL 格納 | 同上。格納先は MySQL | 同上。PG 格納の列と比べると、格納先による違いが分かる |
| frontend 経由・PG 格納 | psql や JDBC の利用者と同じく、frontend に SQL を流す。格納先は PostgreSQL | **利用者が実際に見る結果** |
| frontend 経由・MySQL 格納 | 同上。格納先は MySQL | 同上。PG 格納の列と比べると、格納先による違いが分かる |

Core 直の列が「N/A」の項目は、Core 直では試していない。理由は 4 種類あり、セルには理由も書いた（2026-10-08 に全項目を分類）。

列の説明: 「表示」= セルに出る文字、「理由」= 試していない理由、「項目数」= 該当する項目の数、「ScalarDB が非対応か」= 機能が無いことを意味するかどうか。

| 表示 | 理由 | 項目数 | 例 | ScalarDB が非対応か |
|---|---|---|---|---|
| N/A（機能なし） | ScalarDB にその機能が無い | 34 | NUMERIC/DECIMAL、CHAR(n)/VARCHAR(n) の長さ、型の宣言、集約関数、サーバ側の式（`i + 1`）、IN リスト、OFFSET、文字列から真偽値への変換 | はい |
| N/A（値を渡せない） | API が Java の型（int、LocalTime など）で値を受け取るので、範囲外の値はそもそも渡せない | 9 | INT・BIGINT の範囲外、REAL/DOUBLE の桁あふれ、`TIME 24:00:00` | いいえ（型で守られている） |
| N/A（未作成） | ScalarDB にはあるが、Core 用の操作を書いていない（**テストの抜け**） | 23 | ORDER BY、LIMIT、BETWEEN・OR・IS NULL、条件付きの更新・削除、長い列名、列の数、後からの CREATE INDEX | いいえ |
| N/A（frontend 独自） | frontend だけの機能で、比べる対象が無い | 7 | 1 文 1 万行の書き込み上限、`LIMIT -1` | — |

「未作成」の項目は、Core 直の結果が無いので、原因が ScalarDB 本体か frontend かを判定していない（「どこの問題か」の列では「Core では試せない」と出る）。

### 1.2 記号の意味

列の説明: 「記号」= 結果の表のセルの先頭に付く記号、「意味」= その記号が付く条件、「重さ」= 利用者への影響の大きさ。

| 記号 | 意味 | 重さ |
|---|---|---|
| ✓ | 期待値と同じ結果 | — |
| **✗** | **期待値と違う値が、エラーにならずに返った**。または、エラーになるべき操作が通った | **最も重い**（利用者が気づけない） |
| E | 期待値はエラーではないのに、エラーになった | 中（利用者は気づける） |
| （補足） | 1 項目に複数の文がある場合、**最初に期待値から外れた文**の記号を項目の記号にする（INSERT がエラーになり、その結果 SELECT が 0 行になった場合は E） | — |
| E△ | 期待どおりエラーになったが、SQLSTATE（エラーの種類）が違う | 軽い（SQLSTATE で処理を分けるアプリには影響） |
| E✓ | 期待どおりのエラー（SQL は SQLSTATE も一致。Core 直には SQLSTATE が無いので、エラーになったかだけを見る） | — |
| T | 時間切れ | — |
| N/A | 試していない（上記） | — |

### 1.3 詳細表のセルの読み方

セルは「記号 ＋ 実際に返った値（またはエラー）」。例: `✗ 32768` は「期待値はエラーだが、32768 が入って返ってきた」、`E 22007` は「期待値はエラーではないのに SQLSTATE 22007 のエラーになった」、`E✓ 22003` は「期待どおり SQLSTATE 22003 のエラーになった」。Core 直のエラーは SQLSTATE の代わりに ScalarDB のエラー番号（`DB-CORE-…`）を書く。長い文字列は `xxxx...(len=128)` のように先頭と長さで表す。空白は `␣` で示す。

「どこの問題か」の列は、Core 直と frontend 経由の結果から自動で見立てたもの。Core 直でも frontend 経由でも違えば「ScalarDB 本体」、frontend 経由だけで違えば「frontend」。

## 2. 結果のまとめ

### 表 1. 実行方法ごとの「期待値と違った項目」の数

各セルは **期待値と違った項目の数 ／ その方法で試した項目の数**。「違った」は記号 ✗・E・E△・T の合計。Core 直は N/A の項目を試していないので、試した数が少ない。

列の説明:

- **グループ** — テスト項目の大分類（4 章の詳細表の 4.1〜4.4 に対応）
- **テスト項目数** — そのグループのテスト項目の数（表を作るだけの準備の項目は除く）
- **Native PG（期待値の確認）** — 本物の PostgreSQL で実行した結果。0 ならそのグループの期待値はすべて正しい
- **Core 直・PG 格納 ／ Core 直・MySQL 格納** — frontend を通さず ScalarDB の Java API で実行した結果。格納先が PostgreSQL か MySQL か
- **frontend 経由・PG 格納 ／ frontend 経由・MySQL 格納** — frontend に SQL を流した結果（利用者が見る結果）。格納先が PostgreSQL か MySQL か

| グループ | テスト項目数 | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 |
|---|---|---|---|---|---|---|
| INSERT | 72 | 2 ／ 72 | 9 ／ 49 | 17 ／ 49 | 21 ／ 72 | 32 ／ 72 |
| SELECT | 45 | 0 ／ 45 | 2 ／ 19 | 2 ／ 19 | 0 ／ 45 | 6 ／ 45 |
| UPDATE / DELETE | 16 | 0 ／ 16 | 0 ／ 3 | 0 ／ 3 | 2 ／ 16 | 2 ／ 16 |
| DDL・識別子 | 16 | 0 ／ 16 | 2 ／ 5 | 2 ／ 5 | 5 ／ 16 | 10 ／ 16 |

読み方の例（INSERT）: 72 項目のうち、frontend 経由では PG 格納で 25 項目、MySQL 格納で 32 項目が期待値と違った。Core 直では、試せた 49 項目のうち PG 格納で 9 項目、MySQL 格納で 17 項目が違った。Core 直と frontend 経由は試した項目の数が違うので、違った数をそのまま比べることはできない（表 2 で項目ごとに比べる）。

### 表 2. 期待値と違った項目を、格納先と原因で分けた数（frontend 経由の結果について）

frontend 経由で期待値と違った項目を、(1) どの格納先で違ったか、(2) 何が原因か、で分けて数えた。原因は、同じ項目を Core 直で試した結果から判断した。

列の説明:

- **テスト項目数** — 表 1 と同じ
- **両方の格納先で期待どおり** — frontend 経由で、PG 格納・MySQL 格納の両方とも期待値と同じだった項目の数
- **違った項目（計）** — frontend 経由で、どちらかの格納先で期待値と違った項目の数（＝ 右の 2 列の合計）
- **うち両方の格納先で違う ／ うち MySQL 格納だけで違う** — 違った項目を、どの格納先で違ったかで分けた数（PG 格納だけで違う項目は無かった）
- **原因: ScalarDB 本体** — 違った項目のうち、Core 直でも違った項目の数。frontend を直しても直らない
- **原因: frontend** — 違った項目のうち、Core 直では期待どおりだった項目の数。frontend の修正で直る
- **原因: 不明（Core 直では試せない）** — 違った項目のうち、Core 直の結果が N/A の項目の数（N/A の理由は 1.1。「機能なし」なら frontend の SQL の機能、「未作成」ならテストの抜け）
- **うち ✗（黙って違う値）を含む項目** — 違った項目のうち、どちらかの格納先で記号が ✗ だった項目の数。利用者が気づけない、最も重いもの

| グループ | テスト項目数 | 両方の格納先で期待どおり | 違った項目（計） | うち両方の格納先で違う | うち MySQL 格納だけで違う | 原因: ScalarDB 本体（Core 直でも違う） | 原因: frontend（Core 直では期待どおり） | 原因: 不明（Core 直では試せない） | うち **✗（黙って違う値）** を含む項目 |
|---|---|---|---|---|---|---|---|---|---|
| INSERT | 72 | 39 | 33 | 20（PG 格納だけ 1） | 12 | 17 | 1 | 15 | 19 |
| SELECT | 45 | 39 | 6 | 0 | 6 | 2 | 0 | 4 | 4 |
| UPDATE / DELETE | 16 | 14 | 2 | 2 | 0 | 0 | 0 | 2 | 0 |
| DDL・識別子 | 16 | 5 | 11 | 4（PG 格納だけ 1） | 6 | 3 | 0 | 8 | 3 |

読み方の例（INSERT）: 72 項目のうち 40 項目は両方の格納先で期待どおり、32 項目が違った。違った 32 項目は、25 項目が両方の格納先で、7 項目が MySQL 格納だけで違う。原因で分けると、17 項目は Core 直でも違う（ScalarDB 本体の性質で、frontend を直しても直らない）、15 項目は Core 直では試せない（NUMERIC・CHAR(n) の宣言など、frontend の SQL の機能に関わるもの）。

### 表 3. ScalarDB 本体（Core 直）で期待値と違った項目

frontend を通さなくても期待値と違った項目の一覧。frontend の修正では直らず、ScalarDB 本体か格納先の仕様に原因がある。

列の説明: 「ID」「内容」= 4 章の詳細表のテスト項目、「Core 直・PG 格納 ／ Core 直・MySQL 格納」= Core 直で実行した結果（記号 ＋ 値またはエラー番号）、「frontend 経由でも違うか」= 同じ項目を frontend 経由で実行したときも期待値と違ったか。

| ID | 内容 | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由でも違うか |
|---|---|---|---|---|
| I-SMALL-02 | SMALLINT の最大値 + 1 | ✗ 32768 | ✗ 32768 | 違う |
| I-SMALL-03 | SMALLINT の最小値 − 1 | ✗ -32769 | ✗ -32769 | 違う |
| I-DBL-04 | NaN | ✓ NaN | E DB-CORE-30036 | 違う |
| I-DBL-05 | Infinity | ✓ Infinity | E DB-CORE-30036 | 違う |
| I-DBL-06 | −0 | ✓ -0.0 | ✗ 0.0 | 違う |
| I-STR-08 | NUL 文字を含む | E✓ DB-CORE-30036 | ✗ a\0b | 違う |
| I-DATE-02 | DATE 0999-12-31（ScalarDB の下限 − 1 日） | E DB-CORE-10158 | E DB-CORE-10158 | 違う |
| I-DATE-04 | DATE 10000-01-01（ScalarDB の上限 + 1 日） | E DB-CORE-10158 | E DB-CORE-10158 | 違う |
| I-TIME-03 | TIME 7 桁の小数秒（.1234567） | ✗ 12:00:00.123456 | ✗ 12:00:00.123456 | 違う |
| I-TS-01 | TIMESTAMP のマイクロ秒（.123456） | ✗ 2024-01-01T00:00:00.123 | ✗ 2024-01-01T00:00:00.123 | 違う |
| I-TS-04 | TIMESTAMP 0999-12-31 23:59:59（下限 − 1 秒） | E DB-CORE-10160 | E DB-CORE-10160 | 違う |
| I-TSZ-01 | TIMESTAMPTZ のマイクロ秒 | ✗ 2024-01-01T00:00:00.123Z | ✗ 2024-01-01T00:00:00.123Z | 違う |
| I-KEY-02 | 文字列 PK に 129 文字 | ✓ 1 | E DB-CORE-30036 | 違う |
| I-KEY-03 | VARCHAR(500) の PK に 300 文字 | ✓ 1 | E DB-CORE-30036 | 違う |
| I-KEY-04 | 空文字列の PK | E DB-CORE-10020 | E DB-CORE-10020 | 違う |
| I-KEY-07 | 二次インデックス列に 200 文字 | ✓ ssssssssssssssssssss...(len=200) | E DB-CORE-30036 | 違う |
| I-KEY-08 | クラスタリングキーに 129 文字 | ✓ 1 | E DB-CORE-30036 | 違う |
| S-STR-05 | < 'a'（C 照合順序: '' と 'A'） | ✗ 3 | ✗ 3 | 違う |
| S-STR-06 | > 'A'（C 照合順序: 'a' と 'a '） | ✗ 0 行 | ✗ 5 | 違う |
| D-ID-T64 | 表名 64 文字 | E DB-CORE-30002 | ✓ 1 | 違う |
| D-ID-T65 | 表名 65 文字 | E DB-CORE-30002 | E DB-CORE-30002 | 違う |
| D-KEY-02 | TEXT 7 列の複合 PK（MySQL の索引長の上限 3072 バイトを超える見込み） | ✓ ok | E DB-CORE-30002 | 違う |

## 3. 所見 — 表から読み取ったこと（2026-10-08）

件数: テスト項目は本体 **149 項目**（INSERT 72、SELECT 45、UPDATE/DELETE 16、DDL・識別子 16。ほかに表を作る準備の項目が 2 つ）＋ マルチストレージ 18 項目 ＋ 障害系 3 項目。**期待値（PG の挙動）は 149 項目すべてで Native PG と一致**した。マルチストレージと障害系の**予想は 21 項目すべて当たった**。

### 3.1 新しい発見（findings-log の F-028〜F-036）

「重大度」は 1.2 の記号の重さと同じ考え方（黙って違う値になるものが最も重い）。「該当ケース」は 4 章の詳細表の ID。

| ID | 重大度 | 内容 | 該当ケース | 層 |
|---|---|---|---|---|
| F-028 | **高** | **TIMESTAMP / TIMESTAMPTZ のマイクロ秒が、エラーにならずにミリ秒へ切り捨てられる**（`.123456` → `.123`）。TIME の 7 桁目は四捨五入でなく切り捨て | I-TS-01、I-TSZ-01、I-TIME-03 | ScalarDB 本体（PG・MySQL） |
| F-029 | 中 | DATE / TIMESTAMP は 1000〜9999 年に制限（ScalarDB の仕様）。範囲外・紀元前・`TIME '24:00:00'` は、frontend では 22007 / 22008（日付の書式誤り）として返り、「範囲外」とは分からない | I-DATE-02/04/05、I-TS-04、I-TIME-02 | ScalarDB 本体＋frontend |
| F-030 | **高** | **REAL / DOUBLE の桁あふれが、frontend ではエラーにならず Infinity や 0 として入る**（`REAL ← 3.5e38` → Infinity、`1e-46` → 0、`DOUBLE ← 1e309` → Infinity）。**式の桁あふれも同じ**（`d * 2` が Infinity。PG は 22003） | I-REAL-02/04、I-DBL-02、S-AGG-06 | frontend |
| F-031 | 中 | **MySQL backend では値の意味が変わる**: NaN と ±Infinity は入らない（XX000）、`-0` の符号が消える、NUL 文字を含む文字列は入る（PG は 22021 で拒否） | I-DBL-04/05/06、I-STR-08 | ScalarDB 本体（MySQL） |
| F-032 | 中 | **整数列を、その型の範囲外の値と比べるとエラーになる**（`WHERE i > 2147483648` が 22003。PG は 0 行を返す）。F-012（`整数列 > 12.5`）と同じ原因（リテラルを列の型に変換している） | S-CMP-04/05 | frontend |
| F-033 | **高** | **列名が長いと、作れるのに読めない表ができる（PG backend）**。ScalarDB は列ごとに `before_` を付けた列も作るので、列名が 57 文字以上（計算上。実際に試したのは 63・64 文字）だと物理名が PG の上限 63 文字を超える。PG は黙って切り詰めるため、メタデータとずれて、INSERT は通るのに SELECT が DB-CORE-30028 で失敗する。MySQL backend では表の作成が DB-CORE-30002 で失敗する（理由は示されない）。表名は Core を PG で直接使うと 64 文字から失敗、frontend 経由の PG は 65 文字でも通り、MySQL は 65 文字で失敗 | D-ID-C63/C64、D-ID-T64/T65 | ScalarDB 本体（**推定**。列名は frontend 経由でだけ試した。表名は Core 直でも確認） |
| F-034 | 中 | **列の数の実際の上限は PG の約半分**（同じく `before_` 列のため、1600 列の表は作れない）。エラーは XX000 で理由が無く、1601 列でも 54011 ではなく XX000 | D-COL-1600/1601 | ScalarDB 本体（**推定**。frontend 経由でだけ試した） |
| F-035 | 中 | **MySQL backend のキー長の上限が、宣言と関係なく効く**（F-026 の拡張）: 文字列の PK・クラスタリングキー・索引列は 128 文字まで、TEXT 7 列の複合 PK は作れない（索引長 3072 バイト）、**128 文字を超える値が入った列には後から CREATE INDEX できない**（DB-CORE-30005）。マルチストレージでは同じ DDL が PG 側では通り MySQL 側では失敗する（M-06、M-08） | I-KEY-02/03/07/08、D-KEY-02、D-IDX-01、M-06、M-08 | ScalarDB 本体（MySQL） |
| F-036 | 低 | 空文字列の PK を、frontend は **23502（NOT NULL 違反）**として返す（空文字列を NULL と言っている）。中身は ScalarDB の DB-CORE-10020。ほかに、`LIMIT -1` が通る（PG は 2201W）、`VARCHAR(10485761)`・`NUMERIC(1001)`・`CHAR(0)` の宣言が通る（F-003 と同じ原因） | I-KEY-04、S-LIM-04、D-DECL-02/04/05 | frontend |

### 3.2 これまでの発見の再確認

- F-003（型の修飾子を捨てる）: NUMERIC の丸め・桁あふれ、CHAR の空白埋め、VARCHAR の長さ超過、SMALLINT の範囲がすべて ✗（I-NUM、I-CHAR、I-VC、I-SMALL）。**SMALLINT は Core でも ✗**＝ScalarDB に SMALLINT が無いため
- F-005 / F-021（照合順序）: `s < 'a'`、`s > 'A'` が PG（en_US）でも MySQL でも誤る（S-STR-05/06）。マルチストレージでは、**同じ意味の JOIN が結合の向きで結果を変える**（M-01a は誤り、M-01b は正しい）、主キーと二次インデックスの検索が MySQL で大文字・小文字を区別しない（M-02、M-04a。Core でも同じ）、並べ替えを格納先に任せるクラスタリングキーの順序が誤る（M-03b/c）
- 書き込み件数の上限（frontend の設計、README に記載）: 10,001 行の UPDATE / DELETE は 54000 で拒否（U-CAP-03/05）。`SET scalardb.max_rows_per_write = 0` で通る（U-CAP-04）。INSERT … VALUES の 10,001 行には上限が掛からない（U-CAP-06）

### 3.3 期待どおりだったもの（抜粋）

- 整数の範囲の両端と ±1、BIGINT の 2^53 前後（比較・並べ替えも含めて正確）、DOUBLE の最大値・最小の正の値、文字列の 100 万文字、bytea の 1MB、4 バイト文字、空文字列と NULL の区別、クラスタリングキーの範囲の端（含む・含まない）、IN リストの要素 1 万個、SUM の型の繰り上げ（int → bigint → numeric）、UPDATE の文の原子性
- マルチストレージ: DB をまたぐトランザクションの原子性（M-09、M-10、M-11）と、結果不明時の 40003（M-11）。ただし障害時は COMMIT が返るまで 60 秒〜2 分 30 秒かかる


## 4. 詳細表（全項目）

グループと小分類ごとの表。1 行が 1 テスト項目。

列の説明（4 章のすべての表で共通）:

- **ID** — テスト項目の番号。I = INSERT、S = SELECT、U = UPDATE / DELETE、D = DDL
- **テストの内容** — 何を確かめる項目か
- **入力** — 実行した SQL の要点（長い値は `'x' × 文字数` と略記）
- **期待値（PostgreSQL の結果）** — PostgreSQL ならこうなる、という結果。`E 22003` は SQLSTATE 22003 のエラー、`0 行` は行が返らないこと
- **Native PG ／ Core 直・PG 格納 ／ Core 直・MySQL 格納 ／ frontend 経由・PG 格納 ／ frontend 経由・MySQL 格納** — 各方法で実行した結果。記号（1.2）＋ 実際に返った値またはエラー。N/A は試していない（理由は 1.1）
- **どこの問題か（自動判定）** — Core 直と frontend 経由の結果からの見立て。「ScalarDB 本体」= Core 直でも違う、「frontend」= frontend 経由だけで違う、「Core では試せない」= Core 直が N/A
- **備考** — テスト項目の定義に書いた補足

### 4.1 INSERT — 型の範囲と長さ

1 行を INSERT し、同じ行を SELECT で読み戻す。入れた値がそのまま入るか（または、入れてはいけない値が拒否されるか）を見る。

#### 4.1.1 整数

INT・SMALLINT・BIGINT の最小値・最大値と、その ±1。SMALLINT は ScalarDB に無い型（frontend は INT に置き換える）。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| I-INT-01 | INT の最大値 | `INTEGER ← 2147483647` | 2147483647 | ✓ 2147483647 | ✓ 2147483647 | ✓ 2147483647 | ✓ 2147483647 | ✓ 2147483647 | 問題なし |  |
| I-INT-02 | INT の最小値 | `INTEGER ← -2147483648` | -2147483648 | ✓ -2147483648 | ✓ -2147483648 | ✓ -2147483648 | ✓ -2147483648 | ✓ -2147483648 | 問題なし |  |
| I-INT-03 | INT の最大値 + 1 | `INTEGER ← 2147483648` | E 22003 | E✓ 22003 | N/A（値を渡せない） | N/A（値を渡せない） | E✓ 22003 | E✓ 22003 | 問題なし |  |
| I-INT-04 | INT の最小値 − 1 | `INTEGER ← -2147483649` | E 22003 | E✓ 22003 | N/A（値を渡せない） | N/A（値を渡せない） | E✓ 22003 | E✓ 22003 | 問題なし |  |
| I-SMALL-01 | SMALLINT の最大値 | `SMALLINT ← 32767` | 32767 | ✓ 32767 | ✓ 32767 | ✓ 32767 | ✓ 32767 | ✓ 32767 | 問題なし |  |
| I-SMALL-02 | SMALLINT の最大値 + 1 | `SMALLINT ← 32768` | E 22003 | E✓ 22003 | ✗ 32768 | ✗ 32768 | ✗ 32768 | ✗ 32768 | ScalarDB 本体（PG 格納）、ScalarDB 本体（MySQL 格納） | Core に SMALLINT は無い（INT で代用） |
| I-SMALL-03 | SMALLINT の最小値 − 1 | `SMALLINT ← -32769` | E 22003 | E✓ 22003 | ✗ -32769 | ✗ -32769 | ✗ -32769 | ✗ -32769 | ScalarDB 本体（PG 格納）、ScalarDB 本体（MySQL 格納） |  |
| I-BIG-01 | BIGINT の最大値 | `BIGINT ← 9223372036854775807` | 9223372036854775807 | ✓ 9223372036854775807 | ✓ 9223372036854775807 | ✓ 9223372036854775807 | ✓ 9223372036854775807 | ✓ 9223372036854775807 | 問題なし |  |
| I-BIG-02 | BIGINT の最小値 | `BIGINT ← -9223372036854775808` | -9223372036854775808 | ✓ -9223372036854775808 | ✓ -9223372036854775808 | ✓ -9223372036854775808 | ✓ -9223372036854775808 | ✓ -9223372036854775808 | 問題なし |  |
| I-BIG-03 | BIGINT の最大値 + 1 | `BIGINT ← 9223372036854775808` | E 22003 | E✓ 22003 | N/A（値を渡せない） | N/A（値を渡せない） | E✓ 22003 | E✓ 22003 | 問題なし |  |
| I-BIG-04 | 2^53 + 1（double で表せない整数） | `BIGINT ← 9007199254740993` | 9007199254740993 | ✓ 9007199254740993 | ✓ 9007199254740993 | ✓ 9007199254740993 | ✓ 9007199254740993 | ✓ 9007199254740993 | 問題なし |  |

#### 4.1.2 浮動小数点

REAL（4 バイト）と DOUBLE（8 バイト）の範囲の端、表せない値、NaN・Infinity・−0。double として同じ値なら一致とみなす（表記の違いは問わない）。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| I-REAL-01 | REAL の最大値 | `REAL ← 3.4028235e38` | 3.4028235E38 | ✓ 3.4028235e+38 | ✓ 3.4028235E38 | ✓ 3.4028235E38 | ✓ 3.4028235e+38 | ✓ 3.4028235e+38 | 問題なし |  |
| I-REAL-02 | REAL の最大値を超える | `REAL ← 3.5e38` | E 22003 | E✓ 22003 | N/A（値を渡せない） | N/A（値を渡せない） | E✓ 22003 | E△ XX000 | frontend 経由で違う（MySQL 格納。Core では試せない） | Core は N/A（Java で読んだ時点で Infinity になり、ScalarDB に渡る前に決まるため） |
| I-REAL-03 | 16777217（float4 で表せない整数） | `REAL ← 16777217` | 1.6777216E7 | ✓ 1.6777216e+07 | ✓ 1.6777216E7 | ✓ 1.6777216E7 | ✓ 1.6777216e+07 | ✓ 1.6777216e+07 | 問題なし |  |
| I-REAL-04 | REAL の下限を下回る（1e-46） | `REAL ← 1e-46` | E 22003 | E✓ 22003 | N/A（値を渡せない） | N/A（値を渡せない） | E✓ 22003 | ✗ 0 | frontend 経由で違う（MySQL 格納。Core では試せない） | Core は N/A（Java で読んだ時点で 0 になるため） |
| I-REAL-05 | REAL に 0.1 | `REAL ← 0.1` | 0.1 | ✓ 0.1 | ✓ 0.1 | ✓ 0.1 | ✓ 0.1 | ✓ 0.1 | 問題なし |  |
| I-DBL-01 | DOUBLE の最大値 | `DOUBLE PRECISION ← 1.7976931348623157e308` | 1.7976931348623157E308 | ✓ 1.7976931348623157e+308 | ✓ 1.7976931348623157E308 | ✓ 1.7976931348623157E308 | ✓ 1.7976931348623157e+308 | ✓ 1.7976931348623157e+308 | 問題なし |  |
| I-DBL-02 | DOUBLE の最大値を超える | `DOUBLE PRECISION ← 1e309` | E 22003 | E✓ 22003 | N/A（値を渡せない） | N/A（値を渡せない） | E✓ 22003 | E△ XX000 | frontend 経由で違う（MySQL 格納。Core では試せない） | Core は N/A（Java で読んだ時点で Infinity になるため） |
| I-DBL-03 | DOUBLE の最小の正の値 | `DOUBLE PRECISION ← 4.9e-324` | 5e-324 | ✓ 5e-324 | ✓ 4.9E-324 | ✓ 4.9E-324 | ✓ 4.9e-324 | ✓ 4.9e-324 | 問題なし |  |
| I-DBL-04 | NaN | `DOUBLE PRECISION ← 'NaN'` | NaN | ✓ NaN | ✓ NaN | E DB-CORE-30036 | ✓ NaN | E XX000 | ScalarDB 本体（MySQL 格納） |  |
| I-DBL-05 | Infinity | `DOUBLE PRECISION ← 'Infinity'` | Infinity | ✓ Infinity | ✓ Infinity | E DB-CORE-30036 | ✓ Infinity | E XX000 | ScalarDB 本体（MySQL 格納） |  |
| I-DBL-06 | −0 | `DOUBLE PRECISION ← '-0'` | -0 | ✓ -0 | ✓ -0.0 | ✗ 0.0 | ✓ -0 | ✗ 0 | ScalarDB 本体（MySQL 格納） |  |

#### 4.1.3 正確な数値（宣言）

NUMERIC / DECIMAL(p,s) の桁と丸め。ScalarDB に正確な数値型は無い（frontend は DOUBLE に置き換える）ので、Core 直の列は試せない（N/A）。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| I-NUM-01 | NUMERIC(6,2) の最大値 | `NUMERIC(6,2) ← 9999.99` | 9999.99 | ✓ 9999.99 | N/A（機能なし） | N/A（機能なし） | ✓ 9999.99 | ✓ 9999.99 | 問題なし |  |
| I-NUM-02 | NUMERIC(6,2) の最大値を超える | `NUMERIC(6,2) ← 10000.00` | E 22003 | E✓ 22003 | N/A（機能なし） | N/A（機能なし） | ✗ 10000 | ✗ 10000 | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| I-NUM-03 | NUMERIC(6,2) の丸め（1.005） | `NUMERIC(6,2) ← 1.005` | 1.01 | ✓ 1.01 | N/A（機能なし） | N/A（機能なし） | ✗ 1.005 | ✗ 1.005 | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| I-NUM-04 | 丸めると最大値を超える（9999.995） | `NUMERIC(6,2) ← 9999.995` | E 22003 | E✓ 22003 | N/A（機能なし） | N/A（機能なし） | ✗ 9999.995 | ✗ 9999.995 | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| I-NUM-05 | DECIMAL(20,0) の 20 桁 | `DECIMAL(20,0) ← 99999999999999999999` | 99999999999999999999 | ✓ 99999999999999999999 | N/A（機能なし） | N/A（機能なし） | ✗ 1e+20 | ✗ 1e+20 | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| I-NUM-06 | 精度指定なし NUMERIC の長い値 | `NUMERIC ← 12345678901234567890.123456789` | 12345678901234567890.123456789 | ✓ 12345678901234567890.123456789 | N/A（機能なし） | N/A（機能なし） | ✗ 1.2345678901234567e+19 | ✗ 1.2345678901234567e+19 | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） |  |

#### 4.1.4 文字列

空文字列・NULL・長い文字列（128 / 129 / 70,000 / 1,000,000 文字）・4 バイト文字・NUL 文字・末尾の空白。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| I-STR-01 | 空文字列 | `TEXT ← ''` | '' | ✓ '' | ✓ '' | ✓ '' | ✓ '' | ✓ '' | 問題なし |  |
| I-STR-02 | NULL | `TEXT ← NULL` | NULL | ✓ NULL | ✓ NULL | ✓ NULL | ✓ NULL | ✓ NULL | 問題なし |  |
| I-STR-03 | 128 文字 | `TEXT ← 'x' × 128` | xxxxxxxxxxxxxxxxxxxx...(len=128) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=128) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=128) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=128) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=128) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=128) | 問題なし |  |
| I-STR-04 | 129 文字 | `TEXT ← 'x' × 129` | xxxxxxxxxxxxxxxxxxxx...(len=129) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=129) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=129) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=129) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=129) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=129) | 問題なし |  |
| I-STR-05 | 70,000 文字（64KB 超） | `TEXT ← 'x' × 70,000` | xxxxxxxxxxxxxxxxxxxx...(len=70000) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=70000) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=70000) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=70000) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=70000) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=70000) | 問題なし |  |
| I-STR-06 | 1,000,000 文字 | `TEXT ← 'x' × 1,000,000` | xxxxxxxxxxxxxxxxxxxx...(len=1000000) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=1000000) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=1000000) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=1000000) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=1000000) | ✓ xxxxxxxxxxxxxxxxxxxx...(len=1000000) | 問題なし |  |
| I-STR-07 | 4 バイト文字（絵文字） | `TEXT ← '😀'` | 😀 | ✓ 😀 | ✓ 😀 | ✓ 😀 | ✓ 😀 | ✓ 😀 | 問題なし |  |
| I-STR-08 | NUL 文字を含む | `TEXT ← 'a' + NUL + 'b'` | E 22021 | E✓ 22021 | E✓ DB-CORE-30036 | ✗ a\0b | E✓ 22021 | ✗ a\0b | ScalarDB 本体（MySQL 格納） |  |
| I-STR-09 | 末尾の空白（'a  '） | `TEXT ← 'a  '` | 'a␣␣' | ✓ 'a␣␣' | ✓ 'a␣␣' | ✓ 'a␣␣' | ✓ 'a␣␣' | ✓ 'a␣␣' | 問題なし |  |

#### 4.1.5 文字列（宣言長）

CHAR(n) / VARCHAR(n) の長さちょうど・超過・空白埋め。ScalarDB に長さの宣言は無いので、Core 直の列は N/A。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| I-CHAR-01 | CHAR(5) にちょうど 5 文字 | `CHAR(5) ← 'abcde'` | abcde | ✓ abcde | N/A（機能なし） | N/A（機能なし） | ✓ abcde | ✓ abcde | 問題なし |  |
| I-CHAR-02 | CHAR(5) に 6 文字 | `CHAR(5) ← 'abcdef'` | E 22001 | E✓ 22001 | N/A（機能なし） | N/A（機能なし） | ✗ abcdef | ✗ abcdef | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| I-CHAR-03 | CHAR(5) に 6 文字だが超過分が空白 | `CHAR(5) ← 'abcde '` | abcde | ✓ abcde | N/A（機能なし） | N/A（機能なし） | ✗ 'abcde␣' | ✗ 'abcde␣' | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| I-CHAR-04 | CHAR(5) に 2 文字（空白で埋まる） | `CHAR(5) ← 'ab'` | 'ab␣␣␣' | ✓ 'ab␣␣␣' | N/A（機能なし） | N/A（機能なし） | ✗ ab | ✗ ab | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| I-VC-01 | VARCHAR(5) にちょうど 5 文字 | `VARCHAR(5) ← 'abcde'` | abcde | ✓ abcde | N/A（機能なし） | N/A（機能なし） | ✓ abcde | ✓ abcde | 問題なし |  |
| I-VC-02 | VARCHAR(5) に 6 文字 | `VARCHAR(5) ← 'abcdef'` | E 22001 | E✓ 22001 | N/A（機能なし） | N/A（機能なし） | ✗ abcdef | ✗ abcdef | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| I-VC-03 | VARCHAR(5) に 7 文字だが超過分が空白 | `VARCHAR(5) ← 'abcde  '` | abcde | ✓ abcde | N/A（機能なし） | N/A（機能なし） | ✗ 'abcde␣␣' | ✗ 'abcde␣␣' | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） |  |

#### 4.1.6 真偽値

TRUE と、文字列からの変換。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| I-BOOL-01 | TRUE | `BOOLEAN ← TRUE` | t | ✓ t | ✓ true | ✓ true | ✓ t | ✓ t | 問題なし |  |
| I-BOOL-02 | 文字列 'yes' | `BOOLEAN ← 'yes'` | t | ✓ t | N/A（機能なし） | N/A（機能なし） | ✓ t | ✓ t | 問題なし |  |
| I-BOOL-03 | 文字列 'maybe' | `BOOLEAN ← 'maybe'` | E 22P02 | E✓ 22P02 | N/A（機能なし） | N/A（機能なし） | E✓ 22P02 | E✓ 22P02 | 問題なし |  |

#### 4.1.7 日付時刻

DATE・TIME・TIMESTAMP・TIMESTAMPTZ の範囲の端と秒以下の精度。ScalarDB は DATE / TIMESTAMP を 1000〜9999 年、TIMESTAMP をミリ秒、TIME をマイクロ秒までに制限している。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| I-DATE-01 | DATE 1000-01-01（ScalarDB の下限） | `DATE ← '1000-01-01'` | 1000-01-01 | ✓ 1000-01-01 | ✓ 1000-01-01 | ✓ 1000-01-01 | ✓ 1000-01-01 | ✓ 1000-01-01 | 問題なし |  |
| I-DATE-02 | DATE 0999-12-31（ScalarDB の下限 − 1 日） | `DATE ← '0999-12-31'` | 0999-12-31 | ✓ 0999-12-31 | E DB-CORE-10158 | E DB-CORE-10158 | E 22008 | E 22007 | ScalarDB 本体（PG 格納）、ScalarDB 本体（MySQL 格納） |  |
| I-DATE-03 | DATE 9999-12-31（ScalarDB の上限） | `DATE ← '9999-12-31'` | 9999-12-31 | ✓ 9999-12-31 | ✓ 9999-12-31 | ✓ 9999-12-31 | ✓ 9999-12-31 | ✓ 9999-12-31 | 問題なし |  |
| I-DATE-04 | DATE 10000-01-01（ScalarDB の上限 + 1 日） | `DATE ← '10000-01-01'` | 10000-01-01 | ✓ 10000-01-01 | E DB-CORE-10158 | E DB-CORE-10158 | E 22007 | E 22007 | ScalarDB 本体（PG 格納）、ScalarDB 本体（MySQL 格納） |  |
| I-DATE-05 | DATE 紀元前（4713-01-01 BC） | `DATE ← '4713-01-01 BC'` | 4713-01-01 BC | ✓ 4713-01-01 BC | N/A（未作成） | N/A（未作成） | E 22007 | E 22007 | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| I-TIME-01 | TIME 23:59:59.999999 | `TIME ← '23:59:59.999999'` | 23:59:59.999999 | ✓ 23:59:59.999999 | ✓ 23:59:59.999999 | ✓ 23:59:59.999999 | ✓ 23:59:59.999999 | ✓ 23:59:59.999999 | 問題なし |  |
| I-TIME-02 | TIME 24:00:00 | `TIME ← '24:00:00'` | 24:00:00 | ✓ 24:00:00 | N/A（値を渡せない） | N/A（値を渡せない） | E 22008 | E 22008 | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| I-TIME-03 | TIME 7 桁の小数秒（.1234567） | `TIME ← '12:00:00.1234567'` | 12:00:00.123457 | ✓ 12:00:00.123457 | ✗ 12:00:00.123456 | ✗ 12:00:00.123456 | ✓ 12:00:00.123457 | ✗ 12:00:00.123456 | Core 直のみ（PG 格納。frontend 経由では起きない）、ScalarDB 本体（MySQL 格納） |  |
| I-TS-01 | TIMESTAMP のマイクロ秒（.123456） | `TIMESTAMP ← '2024-01-01 00:00:00.123456'` | 2024-01-01 00:00:00.123456 | ✓ 2024-01-01 00:00:00.123456 | ✗ 2024-01-01T00:00:00.123 | ✗ 2024-01-01T00:00:00.123 | ✗ 2024-01-01 00:00:00.123 | ✗ 2024-01-01 00:00:00.123 | ScalarDB 本体（PG 格納）、ScalarDB 本体（MySQL 格納） |  |
| I-TS-02 | TIMESTAMP のミリ秒（.123） | `TIMESTAMP ← '2024-01-01 00:00:00.123'` | 2024-01-01 00:00:00.123 | ✓ 2024-01-01 00:00:00.123 | ✓ 2024-01-01T00:00:00.123 | ✓ 2024-01-01T00:00:00.123 | ✓ 2024-01-01 00:00:00.123 | ✓ 2024-01-01 00:00:00.123 | 問題なし |  |
| I-TS-03 | TIMESTAMP 1000-01-01（ScalarDB の下限） | `TIMESTAMP ← '1000-01-01 00:00:00'` | 1000-01-01 00:00:00 | ✓ 1000-01-01 00:00:00 | ✓ 1000-01-01T00:00 | ✓ 1000-01-01T00:00 | ✓ 1000-01-01 00:00:00 | ✓ 1000-01-01 00:00:00 | 問題なし |  |
| I-TS-04 | TIMESTAMP 0999-12-31 23:59:59（下限 − 1 秒） | `TIMESTAMP ← '0999-12-31 23:59:59'` | 0999-12-31 23:59:59 | ✓ 0999-12-31 23:59:59 | E DB-CORE-10160 | E DB-CORE-10160 | E 22008 | E 22007 | ScalarDB 本体（PG 格納）、ScalarDB 本体（MySQL 格納） |  |
| I-TS-05 | TIMESTAMP 9999-12-31 23:59:59.999（上限） | `TIMESTAMP ← '9999-12-31 23:59:59.999'` | 9999-12-31 23:59:59.999 | ✓ 9999-12-31 23:59:59.999 | ✓ 9999-12-31T23:59:59.999 | ✓ 9999-12-31T23:59:59.999 | ✓ 9999-12-31 23:59:59.999 | ✓ 9999-12-31 23:59:59.999 | 問題なし |  |
| I-TSZ-01 | TIMESTAMPTZ のマイクロ秒 | `TIMESTAMPTZ ← '2024-01-01 00:00:00.123456+00'` | 2024-01-01 00:00:00.123456+00 | ✗ 2024-01-01 09:00:00.123456+09 | ✗ 2024-01-01T00:00:00.123Z | ✗ 2024-01-01T00:00:00.123Z | ✗ 2024-01-01 09:00:00.123+09 | ✗ 2024-01-01 00:00:00.123+00 | **期待値と Native が違う（期待値を要確認）**、ScalarDB 本体（PG 格納）、ScalarDB 本体（MySQL 格納） |  |
| I-TSZ-02 | TIMESTAMPTZ の時差つき入力（+09） | `TIMESTAMPTZ ← '2024-01-01 09:00:00+09'` | 2024-01-01 00:00:00+00 | ✗ 2024-01-01 09:00:00+09 | ✓ 2024-01-01T00:00:00Z | ✓ 2024-01-01T00:00:00Z | ✗ 2024-01-01 09:00:00+09 | ✓ 2024-01-01 00:00:00+00 | **期待値と Native が違う（期待値を要確認）**、frontend（PG 格納） |  |

#### 4.1.8 バイナリ

空の bytea と 1MB の bytea（値の代わりに長さを比べる）。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| I-BIN-01 | 空の bytea | `BYTEA ← '\x'` | 0 | ✓ 0 | ✓ <blob len=0> | ✓ <blob len=0> | ✓ 0 | ✓ 0 | 問題なし |  |
| I-BIN-02 | 1MB の bytea | `BYTEA ← '\x7878…'（1,048,576 バイト）` | 1048576 | ✓ 1048576 | ✓ <blob len=1048576> | ✓ <blob len=1048576> | ✓ 1048576 | ✓ 1048576 | 問題なし |  |

#### 4.1.9 キー列

主キー・クラスタリングキー・二次インデックスに入れる文字列の長さと特殊な値。MySQL では ScalarDB がキー列を varchar(128) で作る（F-026）。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| I-KEY-01 | 文字列 PK に 128 文字 | `TEXT PK ← 'k' × 128` | 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | 問題なし | MySQL のキー列は varchar(128)（F-026） |
| I-KEY-02 | 文字列 PK に 129 文字 | `TEXT PK ← 'k' × 129` | 1 | ✓ 1 | ✓ 1 | E DB-CORE-30036 | ✓ 1 | E XX000 | ScalarDB 本体（MySQL 格納） |  |
| I-KEY-03 | VARCHAR(500) の PK に 300 文字 | `VARCHAR(500) PK ← 'k' × 300` | 1 | ✓ 1 | ✓ 1 | E DB-CORE-30036 | ✓ 1 | E XX000 | ScalarDB 本体（MySQL 格納） | 宣言は 500 だが、MySQL の物理型は varchar(128) |
| I-KEY-04 | 空文字列の PK | `TEXT PK ← ''` | 1 | ✓ 1 | E DB-CORE-10020 | E DB-CORE-10020 | E 22023 | E 23502 | ScalarDB 本体（PG 格納）、ScalarDB 本体（MySQL 格納） | F-024: ScalarDB は空文字列のパーティションキーを拒否 |
| I-KEY-05 | NULL の PK | `TEXT PK ← NULL` | E 23502 | E✓ 23502 | E✓ DB-CORE-10020 | E✓ DB-CORE-10020 | E✓ 23502 | E✓ 23502 | 問題なし |  |
| I-KEY-06 | 4 バイト文字の PK | `TEXT PK ← '😀'` | 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | 問題なし |  |
| I-KEY-07 | 二次インデックス列に 200 文字 | `索引つき TEXT ← 's' × 200` | 200 | ✓ 200 | ✓ ssssssssssssssssssss...(len=200) | E DB-CORE-30036 | ✓ 200 | E XX000 | ScalarDB 本体（MySQL 格納） | MySQL の索引列は varchar(128) |
| I-KEY-08 | クラスタリングキーに 129 文字 | `TEXT クラスタリングキー ← 'c' × 129` | 1 | ✓ 1 | ✓ 1 | E DB-CORE-30036 | ✓ 1 | E XX000 | ScalarDB 本体（MySQL 格納） |  |

### 4.2 SELECT — 境界値での比較・並び順・件数・集約

境界値の行を入れた共通の表（`t_sel`、5 行）と範囲スキャン用の表（`t_rng`、4 行）に対して問い合わせ、返る行を比べる。返す値は主に行の `id`。

#### 4.2.1 準備

以降の SELECT のケースが使う表を作る。テストではないので集計から外している。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| S-00 | 共通の表を作る（境界値の 5 行、範囲スキャン用の 4 行） | `—` | ok | ✓ ok | ✓ ok | ✓ ok | ✓ ok | ✓ ok | （準備） |  |
| S-00b | 範囲スキャン用の表（Core） | `—` | ok | — 未実行 | ✓ ok | ✓ ok | — 未実行 | — 未実行 | （準備） |  |

#### 4.2.2 境界値での比較

整数・BIGINT・DOUBLE・DATE の境界値と、型の範囲外の値との比較。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| S-CMP-01 | INT の最大値と等しい | `SELECT id FROM t_sel WHERE i = 2147483647` | 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | 問題なし |  |
| S-CMP-02 | INT の最大値 − 1 より大きい | `SELECT id FROM t_sel WHERE i > 2147483646` | 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | 問題なし |  |
| S-CMP-03 | INT の最小値から最大値まで（BETWEEN） | `SELECT id FROM t_sel WHERE i BETWEEN -2147483648 AND 2147483647 ORDER BY id` | 1; 2; 3; 5 | ✓ 1; 2; 3（計 4 行） | N/A（未作成） | N/A（未作成） | ✓ 1; 2; 3（計 4 行） | ✓ 1; 2; 3（計 4 行） | 問題なし |  |
| S-CMP-04 | INT 列を INT の範囲外の値と比べる（> 2147483648） | `SELECT id FROM t_sel WHERE i > 2147483648` | 0 行 | ✓ 0 行 | N/A（値を渡せない） | N/A（値を渡せない） | ✓ 0 行 | E 22003 | frontend 経由で違う（MySQL 格納。Core では試せない） | PG は bigint として比べ、エラーにしない |
| S-CMP-05 | INT 列を INT の範囲外の値と等しい（= 2147483648） | `SELECT id FROM t_sel WHERE i = 2147483648` | 0 行 | ✓ 0 行 | N/A（値を渡せない） | N/A（値を渡せない） | ✓ 0 行 | E 22003 | frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| S-CMP-06 | BIGINT の 2^53 + 1 と等しい | `SELECT id FROM t_sel WHERE b = 9007199254740993` | 3 | ✓ 3 | ✓ 3 | ✓ 3 | ✓ 3 | ✓ 3 | 問題なし | double に変換して比べると 2^53 の行（id 4）も一致してしまう |
| S-CMP-07 | BIGINT の 2^53 と等しい | `SELECT id FROM t_sel WHERE b = 9007199254740992` | 4 | ✓ 4 | ✓ 4 | ✓ 4 | ✓ 4 | ✓ 4 | 問題なし |  |
| S-CMP-08 | BIGINT の最大値と等しい | `SELECT id FROM t_sel WHERE b = 9223372036854775807` | 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | 問題なし |  |
| S-CMP-09 | DOUBLE 列 = 0.1 | `SELECT id FROM t_sel WHERE d = 0.1` | 3 | ✓ 3 | ✓ 3 | ✓ 3 | ✓ 3 | ✓ 3 | 問題なし |  |
| S-CMP-10 | DOUBLE 列 = 0.1 + 0.2（PG では numeric の 0.3 と比べる） | `SELECT id FROM t_sel WHERE d = 0.1 + 0.2` | 0 行 | ✓ 0 行 | N/A（未作成） | N/A（未作成） | ✓ 0 行 | ✓ 0 行 | 問題なし | 0.30000000000000004 の行（id 5）は numeric の 0.3 と一致しない |
| S-CMP-11 | DOUBLE の最大値と等しい | `SELECT id FROM t_sel WHERE d = 1.7976931348623157e308` | 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | 問題なし |  |
| S-CMP-12 | DATE の最大値・最小値 | `SELECT id FROM t_sel WHERE dt = '9999-12-31' OR dt = '1000-01-01' ORDER BY id` | 1; 2 | ✓ 1; 2 | N/A（未作成） | N/A（未作成） | ✓ 1; 2 | ✓ 1; 2 | 問題なし |  |
| S-CMP-13 | うるう日 | `SELECT id FROM t_sel WHERE dt = '2024-02-29'` | 4 | ✓ 4 | ✓ 4 | ✓ 4 | ✓ 4 | ✓ 4 | 問題なし |  |

#### 4.2.3 文字列の比較

空文字列と NULL、大文字と小文字、末尾の空白。期待値は C の照合順序（native はそう設定している）。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| S-STR-01 | 空文字列と等しい（NULL の行は含まない） | `SELECT id FROM t_sel WHERE s = ''` | 3 | ✓ 3 | ✓ 3 | ✓ 3 | ✓ 3 | ✓ 3 | 問題なし |  |
| S-STR-02 | IS NULL | `SELECT id FROM t_sel WHERE s IS NULL` | 4 | ✓ 4 | N/A（未作成） | N/A（未作成） | ✓ 4 | ✓ 4 | 問題なし |  |
| S-STR-03 | = 'a'（'A' と 'a ' は含まない） | `SELECT id FROM t_sel WHERE s = 'a'` | 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | 問題なし |  |
| S-STR-04 | = 'A' | `SELECT id FROM t_sel WHERE s = 'A'` | 2 | ✓ 2 | ✓ 2 | ✓ 2 | ✓ 2 | ✓ 2 | 問題なし |  |
| S-STR-05 | < 'a'（C 照合順序: '' と 'A'） | `SELECT id FROM t_sel WHERE s < 'a' ORDER BY id` | 2; 3 | ✓ 2; 3 | ✗ 3 | ✗ 3 | ✓ 2; 3 | ✗ 3 | Core 直のみ（PG 格納。frontend 経由では起きない）、ScalarDB 本体（MySQL 格納） | Core の結果は id 順とは限らない |
| S-STR-06 | > 'A'（C 照合順序: 'a' と 'a '） | `SELECT id FROM t_sel WHERE s > 'A' ORDER BY id` | 1; 5 | ✓ 1; 5 | ✗ 0 行 | ✗ 5 | ✓ 1; 5 | ✗ 5 | Core 直のみ（PG 格納。frontend 経由では起きない）、ScalarDB 本体（MySQL 格納） |  |

#### 4.2.4 並び順

NULL と極値を含む並べ替え。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| S-ORD-01 | 文字列で並べる（C 照合順序、NULL は最後） | `SELECT id FROM t_sel ORDER BY s, id` | 3; 2; 1; 5; 4 | ✓ 3; 2; 1（計 5 行） | N/A（未作成） | N/A（未作成） | ✓ 3; 2; 1（計 5 行） | ✓ 3; 2; 1（計 5 行） | 問題なし |  |
| S-ORD-02 | INT で並べる（最小 → 最大、NULL は最後） | `SELECT id FROM t_sel ORDER BY i, id` | 2; 3; 5; 1; 4 | ✓ 2; 3; 5（計 5 行） | N/A（未作成） | N/A（未作成） | ✓ 2; 3; 5（計 5 行） | ✓ 2; 3; 5（計 5 行） | 問題なし |  |
| S-ORD-03 | INT の降順（NULL は最初） | `SELECT id FROM t_sel ORDER BY i DESC, id` | 4; 1; 5; 3; 2 | ✓ 4; 1; 5（計 5 行） | N/A（未作成） | N/A（未作成） | ✓ 4; 1; 5（計 5 行） | ✓ 4; 1; 5（計 5 行） | 問題なし |  |
| S-ORD-04 | DOUBLE で並べる | `SELECT id FROM t_sel ORDER BY d, id` | 2; 3; 5; 1; 4 | ✓ 2; 3; 5（計 5 行） | N/A（未作成） | N/A（未作成） | ✓ 2; 3; 5（計 5 行） | ✓ 2; 3; 5（計 5 行） | 問題なし |  |
| S-ORD-05 | BIGINT で並べる（2^53 と 2^53 + 1 を区別） | `SELECT id FROM t_sel ORDER BY b, id` | 2; 5; 4; 3; 1 | ✓ 2; 5; 4（計 5 行） | N/A（未作成） | N/A（未作成） | ✓ 2; 5; 4（計 5 行） | ✓ 2; 5; 4（計 5 行） | 問題なし |  |

#### 4.2.5 件数

LIMIT / OFFSET の端と、IN リストの要素数。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| S-LIM-01 | LIMIT 0 | `SELECT id FROM t_sel ORDER BY id LIMIT 0` | 0 行 | ✓ 0 行 | N/A（未作成） | N/A（未作成） | ✓ 0 行 | ✓ 0 行 | 問題なし |  |
| S-LIM-02 | OFFSET が件数より大きい | `SELECT id FROM t_sel ORDER BY id OFFSET 10` | 0 行 | ✓ 0 行 | N/A（機能なし） | N/A（機能なし） | ✓ 0 行 | ✓ 0 行 | 問題なし |  |
| S-LIM-03 | LIMIT 2147483647 | `SELECT id FROM t_sel ORDER BY id LIMIT 2147483647` | 1; 2; 3; 4; 5 | ✓ 1; 2; 3（計 5 行） | N/A（未作成） | N/A（未作成） | ✓ 1; 2; 3（計 5 行） | ✓ 1; 2; 3（計 5 行） | 問題なし |  |
| S-LIM-04 | LIMIT −1 | `SELECT id FROM t_sel LIMIT -1` | E 2201W | E✓ 2201W | N/A（frontend 独自） | N/A（frontend 独自） | E✓ 2201W | ✗ 通った: 1, 2, 3 | frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| S-IN-01 | IN リストの要素 1 個 | `SELECT count(*) FROM t_sel WHERE id IN (3)` | 1 | ✓ 1 | N/A（機能なし） | N/A（機能なし） | ✓ 1 | ✓ 1 | 問題なし |  |
| S-IN-02 | IN リストの要素 1,000 個 | `SELECT count(*) FROM t_sel WHERE id IN (1, 2, 3, 4, 5, 6, 7, …)` | 5 | ✓ 5 | N/A（機能なし） | N/A（機能なし） | ✓ 5 | ✓ 5 | 問題なし |  |
| S-IN-03 | IN リストの要素 10,000 個 | `SELECT count(*) FROM t_sel WHERE id IN (1, 2, 3, 4, 5, 6, 7, …)` | 5 | ✓ 5 | N/A（機能なし） | N/A（機能なし） | ✓ 5 | ✓ 5 | 問題なし |  |

#### 4.2.6 集約

SUM・AVG・MAX・MIN の結果の型と桁あふれ、空集合での集約。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| S-AGG-01 | SUM(INT) が INT を超える（PG は bigint を返す） | `SELECT sum(i) FROM t_sel WHERE id IN (1, 5)` | 2147483648 | ✓ 2147483648 | N/A（機能なし） | N/A（機能なし） | ✓ 2147483648 | ✓ 2147483648 | 問題なし |  |
| S-AGG-02 | SUM(BIGINT) が BIGINT を超える（PG は numeric を返す） | `SELECT sum(b) FROM t_sel WHERE id IN (1, 5)` | 9223372036854775808 | ✓ 9223372036854775808 | N/A（機能なし） | N/A（機能なし） | ✓ 9223372036854775808 | ✓ 9223372036854775808 | 問題なし |  |
| S-AGG-03 | AVG(INT) の最大値と 1 | `SELECT avg(i) FROM t_sel WHERE id IN (1, 5)` | 1073741824 | ✓ 1073741824.00000000 | N/A（機能なし） | N/A（機能なし） | ✓ 1073741824.00000000 | ✓ 1073741824.00000000 | 問題なし |  |
| S-AGG-04 | MAX / MIN（BIGINT の両端） | `SELECT max(b), min(b) FROM t_sel` | 9223372036854775807, -9223372036854775808 | ✓ 9223372036854775807, -9223372036854775808 | N/A（機能なし） | N/A（機能なし） | ✓ 9223372036854775807, -9223372036854775808 | ✓ 9223372036854775807, -9223372036854775808 | 問題なし |  |
| S-AGG-05 | 空集合の SUM と COUNT | `SELECT sum(i), count(*) FROM t_sel WHERE id > 100` | NULL, 0 | ✓ NULL, 0 | N/A（機能なし） | N/A（機能なし） | ✓ NULL, 0 | ✓ NULL, 0 | 問題なし |  |
| S-AGG-06 | DOUBLE の最大値 × 2 | `SELECT d * 2 FROM t_sel WHERE id = 1` | E 22003 | E✓ 22003 | N/A（機能なし） | N/A（機能なし） | E✓ 22003 | ✗ 通った: Infinity | frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| S-AGG-07 | INT の最大値 + 1（式の桁あふれ） | `SELECT i + 1 FROM t_sel WHERE id = 1` | E 22003 | E✓ 22003 | N/A（機能なし） | N/A（機能なし） | E✓ 22003 | E✓ 22003 | 問題なし |  |
| S-AGG-08 | SUM(DOUBLE) の桁あふれ（最大値 + 最大値） | `SELECT sum(d) FROM t_sel WHERE id IN (1) OR id IN (1)` | 1.7976931348623157e+308 | ✓ 1.7976931348623157e+308 | N/A（機能なし） | N/A（機能なし） | ✓ 1.7976931348623157e+308 | ✓ 1.7976931348623157e+308 | 問題なし | 同じ行を 2 回数えないことの確認（OR の重複） |

#### 4.2.7 範囲スキャン（クラスタリングキー）

クラスタリングキーの範囲の端（含む・含まない）。Core 直では Scan の start / end で同じ範囲を指定する。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| S-RNG-01 | c >= 最小値（含む） | `SELECT c FROM t_rng WHERE p = 1 AND c >= -2147483648 ORDER BY c` | -2147483648; -1; 0; 2147483647 | ✓ -2147483648; -1; 0（計 4 行） | ✓ -2147483648; -1; 0（計 4 行） | ✓ -2147483648; -1; 0（計 4 行） | ✓ -2147483648; -1; 0（計 4 行） | ✓ -2147483648; -1; 0（計 4 行） | 問題なし |  |
| S-RNG-02 | c > 最小値（含まない） | `SELECT c FROM t_rng WHERE p = 1 AND c > -2147483648 ORDER BY c` | -1; 0; 2147483647 | ✓ -1; 0; 2147483647 | ✓ -1; 0; 2147483647 | ✓ -1; 0; 2147483647 | ✓ -1; 0; 2147483647 | ✓ -1; 0; 2147483647 | 問題なし |  |
| S-RNG-03 | c <= 最大値（含む） | `SELECT c FROM t_rng WHERE p = 1 AND c <= 2147483647 ORDER BY c` | -2147483648; -1; 0; 2147483647 | ✓ -2147483648; -1; 0（計 4 行） | ✓ -2147483648; -1; 0（計 4 行） | ✓ -2147483648; -1; 0（計 4 行） | ✓ -2147483648; -1; 0（計 4 行） | ✓ -2147483648; -1; 0（計 4 行） | 問題なし |  |
| S-RNG-04 | c < 最大値（含まない） | `SELECT c FROM t_rng WHERE p = 1 AND c < 2147483647 ORDER BY c` | -2147483648; -1; 0 | ✓ -2147483648; -1; 0 | ✓ -2147483648; -1; 0 | ✓ -2147483648; -1; 0 | ✓ -2147483648; -1; 0 | ✓ -2147483648; -1; 0 | 問題なし |  |
| S-RNG-05 | 0 から 0 まで | `SELECT c FROM t_rng WHERE p = 1 AND c BETWEEN 0 AND 0 ORDER BY c` | 0 | ✓ 0 | ✓ 0 | ✓ 0 | ✓ 0 | ✓ 0 | 問題なし |  |
| S-RNG-06 | 開始 > 終了（空の範囲） | `SELECT c FROM t_rng WHERE p = 1 AND c >= 1 AND c <= -1 ORDER BY c` | 0 行 | ✓ 0 行 | ✓ 0 行 | ✓ 0 行 | ✓ 0 行 | ✓ 0 行 | 問題なし |  |

### 4.3 UPDATE / DELETE — 境界値への更新と書き込み件数の上限

更新・削除の後に SELECT で表の中身を確かめる。エラーになった文が途中まで書いていないか（文の原子性）も見る。

#### 4.3.1 境界値への更新

最大値・NULL・長い文字列への更新と、`i = i + 1` での桁あふれ。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| U-01 | INT の最大値に更新 | `UPDATE t_u_01 SET i = 2147483647 WHERE id = 1` | 2147483647 | ✓ 2147483647 | ✓ 2147483647, x | ✓ 2147483647, x | ✓ 2147483647 | ✓ 2147483647 | 問題なし |  |
| U-02 | i = i + 1 で INT を超える（1 行） | `UPDATE t_u_02 SET i = i + 1 WHERE id = 1` | E 22003 | E✓ 22003 | N/A（機能なし） | N/A（機能なし） | E✓ 22003 | E✓ 22003 | 問題なし |  |
| U-03 | i = i + 1 で 1 行だけ INT を超える（複数行。文の原子性） | `UPDATE t_u_03 SET i = i + 1` | E 22003 | E✓ 22003 | N/A（機能なし） | N/A（機能なし） | E✓ 22003 | E✓ 22003 | 問題なし | 途中まで書かれないこと（id 2 は 1 のまま） |
| U-04 | NULL に更新 | `UPDATE t_u_04 SET i = NULL WHERE id = 1` | NULL | ✓ NULL | ✓ NULL, x | ✓ NULL, x | ✓ NULL | ✓ NULL | 問題なし |  |
| U-05 | キー以外の文字列列を 129 文字に更新 | `UPDATE … SET s = 'z' × 129` | 129 | ✓ 129 | N/A（未作成） | N/A（未作成） | ✓ 129 | ✓ 129 | 問題なし |  |
| U-06 | キー以外の文字列列を 1,000,000 文字に更新 | `UPDATE … SET s = 'z' × 1,000,000` | 1000000 | ✓ 1000000 | N/A（未作成） | N/A（未作成） | ✓ 1000000 | ✓ 1000000 | 問題なし |  |

#### 4.3.2 条件の境界

WHERE の境界ちょうど（含む・含まない）と、0 行の更新・削除。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| U-07 | WHERE i >= 最大値 − 1（含む）で 1 行 | `UPDATE t_u_07 SET s = 'hit' WHERE i >= 2147483646` | 1 | ✓ 1 | N/A（未作成） | N/A（未作成） | ✓ 1 | ✓ 1 | 問題なし |  |
| U-08 | WHERE i > 最大値 − 1（含まない）で 0 行 | `UPDATE t_u_08 SET s = 'hit' WHERE i > 2147483646` | 0 | ✓ 0 | N/A（未作成） | N/A（未作成） | ✓ 0 | ✓ 0 | 問題なし |  |
| U-09 | DELETE で 0 行 | `DELETE FROM t_u_09 WHERE i > 2147483647` | 2 | ✓ 2 | N/A（未作成） | N/A（未作成） | ✓ 2 | ✓ 2 | 問題なし |  |
| U-10 | DELETE で境界値の 1 行 | `DELETE FROM t_u_10 WHERE i >= 2147483646` | 2 | ✓ 2 | ✓ 2 | ✓ 2 | ✓ 2 | ✓ 2 | 問題なし |  |

#### 4.3.3 書き込み件数の上限（frontend は 1 文 1 万行）

10,001 行の表で、上限の前後の件数を更新・削除する。上限は frontend の設計（README に記載）で、PG には無い。Core 直には上限が無いので N/A。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| U-CAP-01 | UPDATE 9,999 行 | `10,001 行の表で UPDATE … WHERE id <= 9999` | 9999 | ✓ 9999 | N/A（frontend 独自） | N/A（frontend 独自） | ✓ 9999 | ✓ 9999 | 問題なし |  |
| U-CAP-02 | UPDATE 10,000 行 | `10,001 行の表で UPDATE … WHERE id <= 10000` | 10000 | ✓ 10000 | N/A（frontend 独自） | N/A（frontend 独自） | ✓ 10000 | ✓ 10000 | 問題なし |  |
| U-CAP-03 | UPDATE 10,001 行 | `10,001 行の表で UPDATE（全行）` | 10001 | ✓ 10001 | N/A（frontend 独自） | N/A（frontend 独自） | E 54000 | E 54000 | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） | frontend は設計上の上限で拒否する（README に記載） |
| U-CAP-04 | 上限を外して（SET … = 0）UPDATE 10,001 行 | `SET scalardb.max_rows_per_write = 0 ; UPDATE（全行）` | 10001 | ✓ 10001 | N/A（frontend 独自） | N/A（frontend 独自） | ✓ 10001 | ✓ 10001 | 問題なし | PG ではこの SET は独自の設定として受け付けられ、何もしない |
| U-CAP-05 | DELETE 10,001 行 | `10,001 行の表で DELETE（全行）` | 0 | ✓ 0 | N/A（frontend 独自） | N/A（frontend 独自） | E 54000 | E 54000 | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） | frontend は設計上の上限で拒否する |
| U-CAP-06 | INSERT（VALUES）で 10,001 行 | `INSERT … VALUES を 10,001 組` | 10001 | ✓ 10001 | N/A（frontend 独自） | N/A（frontend 独自） | ✓ 10001 | ✓ 10001 | 問題なし |  |

### 4.4 DDL・識別子 — 名前の長さ・列の数・型の宣言・キーの長さ

CREATE TABLE などの後に INSERT と SELECT を流し、表が作れるか・使えるかを見る。

#### 4.4.1 識別子の長さ

表名・列名の 63 / 64 / 65 文字。PG は 63 文字に切り詰める。MySQL の上限は 64 文字。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| D-ID-T63 | 表名 63 文字 | `CREATE TABLE <63 文字> …` | 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | ✓ 1 | 問題なし | PG は 63 文字に切り詰めて NOTICE（64・65 文字も同じ名前として扱う）。MySQL の上限は 64 文字 |
| D-ID-T64 | 表名 64 文字 | `CREATE TABLE <64 文字> …` | 1 | ✓ 1 | E DB-CORE-30002 | ✓ 1 | E 42622 | ✓ 1 | ScalarDB 本体（PG 格納） | PG は 63 文字に切り詰めて NOTICE（64・65 文字も同じ名前として扱う）。MySQL の上限は 64 文字 |
| D-ID-T65 | 表名 65 文字 | `CREATE TABLE <65 文字> …` | 1 | ✓ 1 | E DB-CORE-30002 | E DB-CORE-30002 | E 42622 | E XX000 | ScalarDB 本体（PG 格納）、ScalarDB 本体（MySQL 格納） | PG は 63 文字に切り詰めて NOTICE（64・65 文字も同じ名前として扱う）。MySQL の上限は 64 文字 |
| D-ID-C63 | 列名 63 文字 | `列名 <63 文字>` | 7 | ✓ 7 | N/A（未作成） | N/A（未作成） | E 42622 | E XX000 | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） | ScalarDB は before_ を付けた列も作る（MySQL では 64 文字を超える） |
| D-ID-C64 | 列名 64 文字 | `列名 <64 文字>` | 7 | ✓ 7 | N/A（未作成） | N/A（未作成） | E 42622 | E XX000 | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） | ScalarDB は before_ を付けた列も作る（MySQL では 64 文字を超える） |

#### 4.4.2 列の数

100 / 1600 / 1601 列の表。PG の上限は 1600 列。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| D-COL-100 | 100 列 | `CREATE TABLE … （100 列）` | 1 | ✓ 1 | N/A（未作成） | N/A（未作成） | ✓ 1 | ✓ 1 | 問題なし | PG の上限は 1600 列。ScalarDB は列ごとに before_ 列を足すので、物理表は約 2 倍の列になる |
| D-COL-1600 | 1600 列 | `CREATE TABLE … （1600 列）` | 1 | ✓ 1 | N/A（未作成） | N/A（未作成） | E 54011 | E XX000 | frontend 経由で違う（PG 格納。Core では試せない）、frontend 経由で違う（MySQL 格納。Core では試せない） | PG の上限は 1600 列。ScalarDB は列ごとに before_ 列を足すので、物理表は約 2 倍の列になる |
| D-COL-1601 | 1601 列 | `CREATE TABLE … （1601 列）` | E 54011 | E✓ 54011 | N/A（未作成） | N/A（未作成） | E✓ 54011 | E△ XX000 | frontend 経由で違う（MySQL 格納。Core では試せない） | PG の上限は 1600 列。ScalarDB は列ごとに before_ 列を足すので、物理表は約 2 倍の列になる |

#### 4.4.3 型の宣言

PG が拒否する型の宣言（上限を超える長さ・精度、長さ 0）。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| D-DECL-01 | VARCHAR(10485760)（PG の上限） | `CREATE TABLE t_dd_vc1 (id INTEGER, v VARCHAR(10485760), PRIMARY KEY (id))` | ok | ✓ ok | N/A（機能なし） | N/A（機能なし） | ✓ ok | ✓ ok | 問題なし |  |
| D-DECL-02 | VARCHAR(10485761)（PG の上限 + 1） | `CREATE TABLE t_dd_vc2 (id INTEGER, v VARCHAR(10485761), PRIMARY KEY (id))` | E 22023 | E✓ 22023 | N/A（機能なし） | N/A（機能なし） | E✓ 22023 | ✗ 通った | frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| D-DECL-03 | NUMERIC(1000)（PG の上限） | `CREATE TABLE t_dd_n1 (id INTEGER, v NUMERIC(1000), PRIMARY KEY (id))` | ok | ✓ ok | N/A（機能なし） | N/A（機能なし） | ✓ ok | ✓ ok | 問題なし |  |
| D-DECL-04 | NUMERIC(1001)（PG の上限 + 1） | `CREATE TABLE t_dd_n2 (id INTEGER, v NUMERIC(1001), PRIMARY KEY (id))` | E 22023 | E✓ 22023 | N/A（機能なし） | N/A（機能なし） | E✓ 22023 | ✗ 通った | frontend 経由で違う（MySQL 格納。Core では試せない） |  |
| D-DECL-05 | CHAR(0) | `CREATE TABLE t_dd_c0 (id INTEGER, v CHAR(0), PRIMARY KEY (id))` | E 22023 | E✓ 22023 | N/A（機能なし） | N/A（機能なし） | E✓ 22023 | ✗ 通った | frontend 経由で違う（MySQL 格納。Core では試せない） |  |

#### 4.4.4 キーの長さ

TEXT 4 列・7 列の複合主キー。MySQL の索引の長さの上限（3072 バイト）を超えるかどうか。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| D-KEY-01 | TEXT 4 列の複合 PK | `CREATE TABLE t_dd_k4 (a TEXT, b TEXT, c TEXT, d TEXT, v INTEGER, PRIMARY KEY (a, b, c, d)) ; INSERT INTO t_dd_k4 VALUES ('a', 'b', 'c', 'd', 1)` | ok | ✓ ok | ✓ ok | ✓ ok | ✓ ok | ✓ ok | 問題なし |  |
| D-KEY-02 | TEXT 7 列の複合 PK（MySQL の索引長の上限 3072 バイトを超える見込み） | `CREATE TABLE t_dd_k7 (a TEXT, b TEXT, c TEXT, d TEXT, e TEXT, f TEXT, g TEXT, v INTEGER, PRIMARY KEY (a, b, c, d, e, f, g)) ; INSERT INTO t_dd_k7 VALUES ('a', 'b', 'c', 'd', 'e', 'f', 'g', 1)` | ok | ✓ ok | ✓ ok | E DB-CORE-30002 | ✓ ok | E XX000 | ScalarDB 本体（MySQL 格納） |  |

#### 4.4.5 索引

長い値が入った列に、後からインデックスを張る。

| ID | テストの内容 | 入力 | 期待値（PostgreSQL の結果） | Native PG（期待値の確認） | Core 直・PG 格納 | Core 直・MySQL 格納 | frontend 経由・PG 格納 | frontend 経由・MySQL 格納 | どこの問題か（自動判定） | 備考 |
|---|---|---|---|---|---|---|---|---|---|---|
| D-IDX-01 | 128 文字を超える値が入った列に、後から CREATE INDEX | `s に 200 文字を入れてから CREATE INDEX → 長さと検索を確認` | 200 → 1 | ✓ 1 | N/A（未作成） | N/A（未作成） | ✓ 1 | E XX000 | frontend 経由で違う（MySQL 格納。Core では試せない） | MySQL では索引を張ると列が varchar(128) に変わるはず。値が切り詰められないか |

## 5. マルチストレージ — 予想と実測

ScalarDB のマルチストレージ機能で、namespace `ms_pg` を PostgreSQL（照合順序は既定の en_US.utf8）、`ms_my` を MySQL（utf8mb4_0900_ai_ci）に割り当て、1 つの frontend（port 15460）から両方を使った。
比べる相手（同じことができる本物の DB。Apache Spark など）が無いので、**各項目について「あるべき結果」と「予想」を実行前に書いて固定し**、実測と比べた（`cases/cases_multi.py`）。

列の説明（5.1〜5.6 の表で共通。ID・テストの内容・入力は 4 章と同じ）:

| 列の名前 | 意味 |
|---|---|
| あるべき結果 | PostgreSQL の意味ではこうなるべき、という結果 |
| 予想（実行前） | ScalarDB と格納先の仕様・ソースから、実際にはこうなると予想したもの。根拠も書いた |
| 実測: Core 直 | frontend を通さず ScalarDB の Java API で同じ操作をした結果（できるものだけ。括弧内は予想が当たったか） |
| 実測: frontend 経由 | frontend に SQL を流した結果 |
| あるべき結果と一致 | 実測（frontend 経由）があるべき結果と一致したか。記号は 1.2 と同じ |
| 予想は当たったか | ○ 当たり ／ × 外れ |

### 5.1 準備

以降の項目が使う namespace と表を作る（集計の対象外）。

| ID | テストの内容 | 入力 | あるべき結果 | 予想（実行前） | 実測: Core 直 | 実測: frontend 経由 | あるべき結果と一致 | 予想は当たったか |
|---|---|---|---|---|---|---|---|---|
| M-00 | namespace と表を作る | — | — | — | — | ✓ ok | （準備） | — |

### 5.2 文字列の比較が DB で違う

PG 側（大文字・小文字を区別する）と MySQL 側（区別しない）で、同じ文字列の比較や並べ替えの結果が変わるか。

| ID | テストの内容 | 入力 | あるべき結果 | 予想（実行前） | 実測: Core 直 | 実測: frontend 経由 | あるべき結果と一致 | 予想は当たったか |
|---|---|---|---|---|---|---|---|---|
| M-01a | PG の表 → MySQL の表へ、文字列キーで JOIN（PG 'a' / MySQL 'A'） | `SELECT p.k, q.k FROM ms_pg.m1 p JOIN ms_my.m1 q ON q.k = p.k` | 0 行（'a' と 'A' は別の値） | **1 行（a, A）＝誤り**。内側の MySQL の表を主キーで引く Lookup Join になり、MySQL の照合順序（大文字・小文字を区別しない）で 'a' が 'A' に一致する。主キーの Get には Java 側の再チェックが無い（F-021 で Core の Get 'A' が 'a' を返した） | N/A | ✗ a, A | ✗ | ○ |
| M-01b | MySQL の表 → PG の表へ、同じ JOIN（向きだけ逆） | `SELECT q.k, p.k FROM ms_my.m1 q JOIN ms_pg.m1 p ON p.k = q.k` | 0 行 | 0 行（正しい）。内側の PG の表を 'A' で引くので一致しない。**M-01a と同じ意味の SQL なのに、結合の向きで結果が変わる** | N/A | ✓ 0 行 | ✓ | ○ |
| M-01c | キー以外の文字列列どうしで JOIN（PG 'B','a' / MySQL 'b','A'） | `SELECT p.name, q.name FROM ms_pg.m3 p JOIN ms_my.m3 q ON q.name = p.name` | 0 行 | 0 行（正しい）。キー以外の列なので frontend の Hash Join（Java のバイナリ比較）になる | N/A | ✓ 0 行 | ✓ | ○ |
| M-02 | MySQL の表を主キー 'a' で引く（実在するのは 'A'） | `SELECT k FROM ms_my.m1 WHERE k = 'a'` | 0 行 | **1 行（A）＝誤り**。主キーの Get が MySQL で大文字・小文字を区別せずに一致する（F-021） | ✗ A（予想 ○） | ✗ A | ✗ | ○ |
| M-03a | PG の表と MySQL の表を UNION ALL して ORDER BY | `SELECT name FROM ms_pg.m3 UNION ALL SELECT name FROM ms_my.m3 ORDER BY name` | A, B, a, b（C の照合順序） | A, B, a, b（正しい）。UNION の後の並べ替えは frontend が Java で行う | N/A | ✓ A; B; a（計 4 行） | ✓ | ○ |
| M-03b | PG の表のクラスタリングキー（'B','a','c'）で並べる | `SELECT c FROM ms_pg.m3c WHERE p = 1 ORDER BY c` | B, a, c（C の照合順序） | **a, B, c＝誤り**。並べ替えを格納先の PG（msdb は既定の en_US.utf8）に任せるので、en_US の順になる（F-005 と同じ原因） | N/A | ✗ a; B; c | ✗ | ○ |
| M-03c | MySQL の表のクラスタリングキー（'B','a','c'）で並べる | `SELECT c FROM ms_my.m3c WHERE p = 1 ORDER BY c` | B, a, c | **a, B, c＝誤り**。並べ替えを MySQL（utf8mb4_0900_ai_ci）に任せる | N/A | ✗ a; B; c | ✗ | ○ |
| M-04a | MySQL の二次インデックスで 'alice' を検索（'alice' と 'ALICE' がある） | `SELECT id FROM ms_my.m4 WHERE name = 'alice' ORDER BY id` | 1 行（id 1） | **2 行（1, 2）＝誤り**。インデックス経由の Scan には Java 側の再チェックが無い（作者の設計メモ #7） | ✗ 1; 2（予想 ○） | ✗ 1; 2 | ✗ | ○ |
| M-04b | PG の二次インデックスで同じ検索 | `SELECT id FROM ms_pg.m4 WHERE name = 'alice' ORDER BY id` | 1 行 | 1 行（正しい）。PG の en_US は等号では大文字・小文字を区別する（決定的な照合順序） | ✓ 1（予想 ○） | ✓ 1 | ✓ | ○ |

### 5.3 同じ宣言で DB により型が違う

同じ CREATE TABLE でも、格納先によって物理型や上限が違うことの影響。

| ID | テストの内容 | 入力 | あるべき結果 | 予想（実行前） | 実測: Core 直 | 実測: frontend 経由 | あるべき結果と一致 | 予想は当たったか |
|---|---|---|---|---|---|---|---|---|
| M-05a | REAL 列どうしを DB をまたいで比べる（両方 0.1） | `SELECT p.id FROM ms_pg.m5 p JOIN ms_my.m5 q ON q.r = p.r` | 1 行（どちらも real の 0.1） | 1 行（正しい）。MySQL の物理型は double だが、ScalarDB は FLOAT として読み直すので Java の比較では同じ値になる | N/A | ✓ 1 | ✓ | ○ |
| M-05b | MySQL 側の REAL 列を、格納されている値のまま読む | `SELECT r FROM ms_my.m5 WHERE id = 1 ; SELECT r FROM ms_pg.m5 WHERE id = 1` | 0.1 と 0.1 | 0.1 と 0.1（正しい）。ScalarDB の FLOAT として読むので double の誤差は見えない | N/A | ✓ 0.1 | ✓ | ○ |
| M-06 | 同じ DDL の文字列キーに 129 文字（PG 側と MySQL 側） | `INSERT … ('k' × 129) を ms_pg と ms_my に` | どちらも成功 | **PG 側は成功、MySQL 側は失敗**（キー列が varchar(128)。F-026） | E DB-CORE-30036（予想 ○） | E XX000 | E | ○ |

### 5.4 DB 間のデータ移動

PG 側の表から MySQL 側の表へ INSERT … SELECT で移すとき、MySQL 側の制約に当たるか。

| ID | テストの内容 | 入力 | あるべき結果 | 予想（実行前） | 実測: Core 直 | 実測: frontend 経由 | あるべき結果と一致 | 予想は当たったか |
|---|---|---|---|---|---|---|---|---|
| M-07 | PG の 'a' と 'A' を MySQL の表へ INSERT … SELECT | `INSERT INTO ms_my.m7 SELECT k FROM ms_pg.m7 ; SELECT k FROM ms_my.m7 ORDER BY k` | 2 行が移る | **失敗し、0 行**。MySQL の主キーが大文字・小文字を区別しないので、同じトランザクションの 2 行目が衝突する（F-021。文全体が取り消される） | N/A | E 40001 | E | ○ |
| M-08 | PG の 129 文字のキーを MySQL の表へ INSERT … SELECT | `INSERT INTO ms_my.m8 SELECT k FROM ms_pg.m8 ; SELECT count(*) FROM ms_my.m8` | 1 行が移る | **失敗し、0 行**（MySQL のキー列は 128 文字まで） | N/A | E XX000 | E | ○ |

### 5.5 原子性

PG 側と MySQL 側の両方に書くトランザクションで、片方が失敗したとき、もう片方も取り消されるか。

| ID | テストの内容 | 入力 | あるべき結果 | 予想（実行前） | 実測: Core 直 | 実測: frontend 経由 | あるべき結果と一致 | 予想は当たったか |
|---|---|---|---|---|---|---|---|---|
| M-09 | PG 側の UPDATE と、MySQL 側で失敗する INSERT（129 文字のキー）を 1 つのトランザクションで COMMIT | `BEGIN ; UPDATE ms_pg.acc … ; INSERT INTO ms_my.lg ('k' × 129) ; COMMIT ; 両方を確認` | すべて成功（PG の意味では 129 文字のキーに問題は無い）。v = 99、lg は 1 行 | **INSERT の文は通り、COMMIT で失敗する**（ScalarDB は書き込みを COMMIT まで溜め、prepare で MySQL に書くため）。**PG 側も反映されず v = 100 のまま、lg は 0 行**（原子性は保たれる） | N/A | E XX000 | E | ○ |

### 5.6 namespace の割り当て

割り当てに無い namespace と、DROP SCHEMA の扱い。

| ID | テストの内容 | 入力 | あるべき結果 | 予想（実行前） | 実測: Core 直 | 実測: frontend 経由 | あるべき結果と一致 | 予想は当たったか |
|---|---|---|---|---|---|---|---|---|
| M-12a | 割り当てに無い namespace に表を作る | `CREATE SCHEMA IF NOT EXISTS ms_unmapped ; DROP TABLE IF EXISTS ms_unmapped.t ; CREATE TABLE ms_unmapped.t (id INTEGER, PRIMARY KEY (id)) …` | 作れる | 作れる。既定の格納先（PG）に置かれる（F-027 で確認済み） | N/A | ✓ 1 | ✓ | ○ |
| M-12b | 表が残っている namespace を DROP SCHEMA（CASCADE なし） | `DROP SCHEMA ms_unmapped` | エラー 2BP01（中に表がある） | エラー。ScalarDB の dropNamespace は表が残っていると失敗する。ただし SQLSTATE は 2BP01 ではなく内部エラー（XX000 系） | N/A | E△ 42601 | E△ | ○ |
| M-12c | DROP SCHEMA … CASCADE | `DROP SCHEMA ms_unmapped CASCADE ; SELECT count(*) FROM ms_unmapped.t` | 中の表ごと消える。その後の SELECT は 42P01 | 中の表ごと消える（正しい）。frontend は CASCADE のとき表を消してから namespace を消す（QueryParser.java:2399-2411。予想を書く前にコードで確認） | N/A | E✓ 42P01 | E✓ | ○ |

### 5.7 障害系（M-10・M-11）— 片方の DB を止めて COMMIT する

PG 側と MySQL 側の両方に書くトランザクションの COMMIT の直前に、片方の DB のコンテナを止めて、データがそろうか（原子性）と、どう報告されるかを見た。

手順 = `tools/run_multi_failure.sh`（生の出力 = `results/multi-failure-raw.txt`、2026-10-08 14:37〜14:41 に実行）。どのケースも「`BEGIN` → `UPDATE ms_pg.acc`（PG）→ `INSERT INTO ms_my.lg`（MySQL）→ コンテナを止める → `COMMIT`」。予想はスクリプトの冒頭に、実行前に書いて固定した。

列の説明: 「障害」= COMMIT の直前に起こした障害、「実測」= 実際の COMMIT の結果（待った時間を含む）と、その後の両方の DB の状態。ほかの列は 5.1〜5.6 の表と同じ。

| ID | 障害 | あるべき結果 | 予想（実行前） | 実測 | あるべき結果と一致 | 予想は当たったか |
|---|---|---|---|---|---|---|
| M-10 | MySQL を **stop** して COMMIT | COMMIT がエラーなら、両方とも反映されない | COMMIT がエラー。PG 側も反映されない。MySQL の再起動後は普通に読める | COMMIT が **60 秒待ってから** `DB-CORE-30036: Preparing records failed. Details: DB-CORE-30067: Getting the virtual table information failed`（**メッセージが原因＝MySQL への接続失敗と合っていない**）。`acc.v = 100`、`lg` は 0 行（**原子性は保たれた**）。MySQL の再起動後、すぐに読めた | ✓（整合性）／遅い・メッセージ不適切 | ○（60 秒待つことは予想外） |
| M-10b | MySQL を **20 秒 pause** して COMMIT | 成功する | 約 20 秒待ってから成功 | COMMIT が **21.9 秒**待ってから成功。`acc.v = 99`、`lg = f10b` | ✓ | ○ |
| M-11 | Coordinator 表がある PG を **stop** して COMMIT | 結果が不明なら「不明」と伝え、最終的に両方そろう | COMMIT がエラー。PG 再起動後、`acc.v = 100`。MySQL 側の書きかけは後片付けで消える | COMMIT が **2 分 30 秒待ってから** `DB-CORE-40003: The coordinator status is unknown`（frontend は SQLSTATE `40003` statement_completion_unknown に対応付ける＝**正しい**）。PG 再起動直後も 20 秒後も `acc.v = 100`、`lg` に `f11` は無い。Coordinator 表にこのトランザクションが **ABORTED（4）** と記録された（**最終的に両方そろった**） | ✓（整合性）／非常に遅い | ○（2 分 30 秒待つことは予想外） |

所見:
- **原子性と、結果不明時の通知（40003）は正しい。** 予想はすべて当たった
- 予想していなかった点が 2 つある。(1) **障害時に COMMIT が返るまで 60 秒〜2 分 30 秒かかる**（接続プールの接続取得の待ち時間と再試行の合計と思われる。未調査）。アプリの要求タイムアウトより長いと、アプリ側では「タイムアウト」に見え、40003 を受け取れない (2) MySQL 停止時のエラーメッセージ「Getting the virtual table information failed」は、原因（接続できない）を示していない


