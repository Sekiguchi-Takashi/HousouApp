# HANDOFF — HousouApp（放送室）

## 現況
v1.0 / versionCode 1。Kotlin、UIは全てコード生成（XMLレイアウトなし）。
1APK 2ロール（console / terminal）。`Store.mode` で分岐し `MainActivity.route()` が画面を決める。

## ファイル構成

| ファイル | 役割 |
|---|---|
| `Proto.kt` | ポート番号・サンプルレート・定数 |
| `Net.kt` | 自IP取得、ブロードキャストアドレス列挙、TCP制御1往復 |
| `Store.kt` | SharedPreferences（設定・端末台帳・定型文・予約・ログ） |
| `Registry.kt` | `Dev` エンティティと端末レジストリ（in-memory + 永続化） |
| `Audio.kt` | Player / Recorder / Sender / Receiver、チャイム合成 |
| `Diag.kt` | AI推論ルール（スコア、アラート、障害推定、帯域推奨） |
| `Assistant.kt` | 日本語自然言語→操作意図のローカル写像 |
| `TerminalService.kt` | 子機常駐（アナウンス・制御サーバ・受話・TTS） |
| `ConsoleService.kt` | 親機常駐（探索・ポーリング・スケジューラ・受話）＋ `Targeting` |
| `Ui.kt` | 配色とビュー生成ヘルパ |
| `MainActivity.kt` | スプラッシュ／役割選択／ログイン／画面切替 |
| `ConsoleUi.kt` | コンソール7タブ |
| `TerminalUi.kt` | 子機の待機／放送中画面 |

## 設計上の約束

- **制御は必ず `Net.ctrl()` 経由の1行JSON往復**。新コマンドは `TerminalService.exec()` の `when` に追加し、応答は `statusJson()` を返す。
- **音声はヘッダなし生PCM**。`Audio.Sender` → `Audio.Receiver` が対。ポートを増やす場合は `Proto.kt` に定数を置く。
- **UI再描画**は `ConsoleUi.refreshers`（`() -> Unit` のリスト）に登録し、1.5秒tickとサービスからの `push()` で一括実行。`render()` は refreshers をクリアするので、タブ生成関数の中で毎回 add し直す。
- **ネットワークはすべてバックグラウンドスレッド**。`bg { }` / `ui { }` を使う。
- 端末IDは `Store.deviceId`（UUID先頭8桁）。IPは変わる前提で、レジストリのキーには使わない。

## 次にやるなら

1. **音声ファイル放送** — SAFで音源選択 → MediaExtractor/MediaCodecでPCM化 → `Audio.Sender` に流す（`bcast_start` の再利用でよい）
2. **QRコード端末登録** — 子機がIP+IDをQR表示、親機がカメラで読む（依存追加が必要）
3. **BGM再生** — ループ再生用の別ストリームを `bcast_start` と分離し、放送時にダッキング
4. **災害時自動放送** — `ConsoleService.startScheduler()` と同じ枠組みで、緊急地震速報インテント受信をトリガに `tts(urgent=true)` を全館送出
5. **複数建物** — `Store.building` を配列化し、`Dev` に `buildingId` を追加

## 既知の注意点

- AP隔離（クライアント分離）がONのWi-Fiでは端末間通信ができず自動検出も失敗する
- 端末のTTS日本語データ未導入だと読み上げが無音になる
- スケジュールはコンソール常駐が前提（端末単独では発火しない）
