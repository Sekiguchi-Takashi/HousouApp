# PEBBLE_COMMON.md — Pebble開発 共通規約 (Appathy)

全Pebbleプロジェクト共通の規約。新チャットの冒頭にこのファイルと
該当プロジェクトの HANDOFF.md を貼れば、文脈を完全に再現できる。

- ブランド: Appathy (Less Motivation, More Automation)
- GitHubアカウント: Sekiguchi-Takashi
- 開発環境: Termux のみ (PCなし)

---

## 1. ビルドスタック(非交渉・固定)

| 項目 | 値 |
|---|---|
| ビルドツール | pebble-tool (PyPI, uvでインストール) |
| SDK | Pebble SDK latest (`pebble sdk install latest`) |
| ビルド実行場所 | GitHub Actions (ubuntu-latest) のみ |
| ローカルビルド | しない (TermuxはARM、SDKはx86_64 Linux向けで動かない) |
| 外部依存 | ゼロ (npmパッケージ・外部ライブラリ一切なし) |
| リソースファイル | ゼロ (システムフォントのみ、画像・カスタムフォントなし) |
| 対象プラットフォーム | aplite / basalt / chalk / diorite |
| SDKキャッシュ | Actionsで `~/.pebble-sdk` をキャッシュ |
| 成果物 | `build/*.pbw` を artifact としてアップロード |

対象プラットフォーム対応機種:
- aplite: Pebble Classic / Pebble Steel (白黒)
- basalt: Pebble Time / Time Steel (カラー)
- chalk: Pebble Time Round (丸型)
- diorite: Pebble 2 / Core 2 Duo 相当 (白黒)

## 2. 標準ワークフロー (.github/workflows/build.yml)

```yaml
name: Build <ProjectName>
on:
  push:
    branches: [ main ]
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: astral-sh/setup-uv@v5
      - run: uv tool install pebble-tool
      - uses: actions/cache@v4
        with:
          path: ~/.pebble-sdk
          key: pebble-sdk-${{ runner.os }}-v1
      - run: pebble sdk install latest || printf 'SDK already installed\n'
      - run: pebble build
      - uses: actions/upload-artifact@v4
        with:
          name: <project>-pbw
          path: build/*.pbw
          if-no-files-found: error
```

## 3. デプロイ手順 (Termux)

ZIP受領時の実行手順は常にこの4行のみ。`&&`で繋がない。

```
cd ~
cp /sdcard/Download/<Project>_vX.X.zip .
unzip -o <Project>_vX.X.zip
bash ~/<Project>/deploy.sh "vX.X 変更内容の要約"
```

deploy.sh は Appathy標準の冪等スクリプト:
1. 冒頭で自フォルダに `cd` (ホームでのgit init事故防止)
2. `git config --global github.token` からトークン読出し
3. GitHub APIでリポジトリ作成 (既存なら HTTP 422 が返るが続行)
4. `.git` があれば init をスキップ
5. remote を remove → add で再設定 (冪等)
6. `git add -A` → commit → push

ZIPファイル名は毎回バージョン番号付きで変える。
対話入力 (`read` 等) を含むコマンドは絶対に使わない。
コマンド内で `echo` は使わない (`printf` を使用)。

## 4. プロジェクト構成(標準)

```
<Project>/
├── package.json              # アプリ定義(UUID, プラットフォーム, messageKeys)
├── wscript                   # waf ビルド定義(全プロジェクト同一・変更不要)
├── deploy.sh                 # Appathy標準デプロイ(REPO変数のみ書き換え)
├── HANDOFF.md                # プロジェクト個別の引き継ぎ
├── .gitignore                # build/ と .lock-waf*
├── .github/workflows/build.yml
└── src/
    ├── c/main.c              # 時計側(C)
    └── pkjs/index.js         # スマホ側(PebbleKit JS)
```

UUIDはプロジェクトごとに必ず新規生成する(重複すると上書きインストールになる)。

## 5. プラットフォーム制約(重要)

### 日本語表示は言語パック依存(2026-08 更新)
素のシステムフォントに日本語グリフはないが、**Pebble公式アプリから
日本語言語パック(Kuro氏製 v5等)をインストールすると、システムフォントが
日本語入りに差し替わり、自作アプリの `fonts_get_system_font()` の文字列も
日本語で表示される**。漢字10,375字をカバー。言語パックはデバイス非依存。

ただし以下を前提に設計すること:
- 言語パックは**利用者側の任意インストール**。他人に配布するアプリでは
  入っていない可能性がある → 日本語ラベルとASCIIラベルを両方持ち、
  アプリ設定で切替できるようにする(既定はASCII)
- 全角のため**表示可能文字数は半分**(ASCII 20文字 ≒ 日本語 10文字)
- 言語パックは同時に1つのみ。後入れで上書きされる
- 古いOG Pebble(aplite)は RAM が小さく動かない可能性がある
- システムUI(メニュー・通知)も日本語化される
- アプリからは「言語パックが入っているか」を判定するAPIがない →
  自動判定はせず、利用者に設定させる
- ソースコードはUTF-8で保存する(C文字列に日本語を直接書ける)

アプリ内リソースとしてのカスタム日本語フォント同梱は引き続き採用しない
(サイズが大きく「リソースゼロ」方針と衝突するため)。

### スマホのOS通知は読めない
LINE/Gmail等の通知はPebbleOSがシステム側で処理する仕様で、
サードパーティアプリに通知内容を渡すAPIは存在しない。
実通知の全文表示はコンパニオンアプリの通知転送機能(標準)に任せ、
自作アプリは自前データの表示に徹する。

### エミュレータはTermuxで動かない
QEMUベースのエミュレータバイナリはx86_64 Linux向け。
実機なしの確認は CloudPebble (cloudpebble.repebble.com、ブラウザで動作) か
GitHub Actions上でのスクリーンショット生成で行う。

### artifactは二重zip
Actionsからダウンロードしたzipを解凍し、中の `.pbw` 単体を開くこと。
zipのまま開くとインストールに失敗する。

## 6. インストールと動作確認

1. Actions → 該当ジョブ → Artifacts から `<project>-pbw` をダウンロード
2. 解凍して中の `.pbw` をタップ → 「Pebbleで開く」
3. Pebbleアプリのアプリ一覧に名前が出れば転送成功
   (サイドローディングはサムネイル画像なし。名前だけ表示されるのが正常)
4. ウォッチフェイス → 時計本体で上下ボタンを押して切り替え
   ウォッチアプリ → 真ん中ボタン → アプリメニューから起動
5. ログ確認: Pebbleアプリで Dev Connect を有効化 → `pebble logs --phone <IP>`

設定画面(歯車)を出すには package.json に
`"capabilities": ["configurable"]` が必要。

## 7. AppMessage (時計 ⇔ スマホ 通信) の作法

- キーは package.json の `messageKeys` に文字列で宣言し、
  C側では `MESSAGE_KEY_<名前>` で参照する
- 時計→スマホ: `app_message_outbox_begin` → `dict_write_*` → `outbox_send`
- スマホ→時計: `Pebble.sendAppMessage({KEY: value}, success, failure)`
- 受信は `app_message_register_inbox_received` / JS側は `appmessage` イベント
- `app_message_open(inbox_size, outbox_size)` のサイズは余裕をもって指定
- 文字列受信時は `strncpy` + 末尾NUL保証を徹底する(バッファオーバーラン防止)
- 送信失敗ハンドラを必ず登録し、UIにエラーを出す(無反応が一番困る)

## 8. 現行プロジェクト一覧

| プロジェクト | 種別 | 内容 | 版 |
|---|---|---|---|
| pebble-testface | watchface | 時刻/日付/電池/BT。動作確認用の最小構成 | v1.0 |
| KakeiWatch | watchapp | 手首家計簿入力。Money(1000円刻み)+8カテゴリ(100円刻み)、CSVエクスポート | v1.1 |
| InfoFace | watchface | 時計+Open-Meteo天気詳細。雨90分前にダブル振動 | v1.0 |

## 9. チャット分割の運用

- プロジェクトごとに1チャット。共通規約の変更はこのファイルに集約する
- 新チャット冒頭に貼るもの: PEBBLE_COMMON.md + そのプロジェクトの HANDOFF.md
- 他アプリとの契約(例: KakeiWatch → KakeiApp の CSV フォーマット)は
  契約ファイルの唯一のオーナーを片方のチャットに固定し、
  もう一方は契約を読むだけで変更提案をしない

## 10. 全プロジェクト共通のハマりどころ

- `git init` をホームディレクトリで実行しない (deploy.sh冒頭のcdで防止済み)
- Push Protection (GH013): トークンをコミットに含めない。
  トークンは `git config --global github.token` に登録して参照する
- UUID重複: 新規プロジェクトでは必ずUUIDを生成し直す
- pebble-toolは活発に更新中。ビルドが突然壊れたらSDK側の仕様変更を疑い、
  Actionsのログを確認する
