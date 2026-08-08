# HANDOFF — HousouApp（放送室）

## 現況
v1.1 / versionCode 2。Kotlin、UIは全てコード生成（XMLレイアウトなし）。
1APK 2ロール（console / terminal）。`Store.mode` で分岐し `MainActivity.route()` が画面を決める。

## ファイル構成

| ファイル | 役割 |
|---|---|
| `Proto.kt` | ポート番号・サンプルレート・定数 |
| `Net.kt` | 自IP取得、ブロードキャストアドレス列挙、TCP制御1往復 |
| `Store.kt` | SharedPreferences（設定・端末台帳・定型文・予約・ログ） |
| `Registry.kt` | `Dev` エンティティと端末レジストリ（in-memory + 永続化） |
| `Audio.kt` | Player / Recorder / Sender / Receiver、チャイム合成 |
| `Mixer.kt` | コンソール側ミキサー。マイク/ファイル/BGMを宛先ごとに合成、ダッキング、再生セッションの参照カウント |
| `Decoder.kt` | MediaExtractor+MediaCodec で音声ファイル→16kHzモノラルPCM |
| `Library.kt` | 音源台帳と .pcm 保存 |
| `Qr.kt` / `ScanActivity.kt` | 端末登録QRの生成とCameraX読み取り |
| `Suggest.kt` | ログ分析によるAI放送支援（放送先/予約/お気に入り） |
| `Alerts.kt` | 端末異常の管理者通知（状態変化時のみ） |
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

## v1.1 で追加した約束

- **音声送出はすべて `Mixer` を通す**。新しい音源系統を足す場合は `micIps` / `fileIps` / `bgmIps` と同じ形で「宛先集合を持つ系統」として追加し、`loop()` の合成に加える。端末への `bcast_start` / `bcast_stop` は `open()` / `close()` の参照カウントに任せる（直接送らない）。
- **保存PCMは常に16kHz**。8kHz運用は `Mixer.step()` の間引きで吸収する。
- **ログは `store.log(kind, text, target, tag)`** で対象と定型文名まで残す。`Suggest` はこの2つのフィールドだけを見ている。
- **通話は従来どおり `ConsoleService.startTx`（`Audio.Sender`）**。Mixerとマイクを奪い合うため、`startPtt()` で通話中を弾いている。

## 次にやるなら

1. **災害時自動放送** — `ConsoleService.startScheduler()` と同じ枠組みで、緊急地震速報インテント受信をトリガに `tts(urgent=true)` を全館送出
2. **複数建物** — `Store.building` を配列化し、`Dev` に `buildingId` を追加
3. **故障予兆検知** — `Diag.score` の時系列を保存し、劣化トレンドで予告する
4. **SIP/IP電話連携** — 通話系統を SIP スタックに差し替え。`Audio.Sender/Receiver` の口は流用できる
5. **Bluetoothスピーカー** — 端末側 `Audio.Player` の AudioAttributes を切替

## 既知の注意点

- AP隔離（クライアント分離）がONのWi-Fiでは端末間通信ができず自動検出も失敗する
- 端末のTTS日本語データ未導入だと読み上げが無音になる
- スケジュールはコンソール常駐が前提（端末単独では発火しない）
- BGM/音声ファイルもコンソールが送出元。コンソールのプロセスが落ちると止まる
- QR読み取りにはカメラ権限が要る。初回は `ScanActivity` 内で要求する
- 長尺音源は取り込み時に全デコードするため、15分でクリップされる（`Decoder.MAX_SECONDS`）
