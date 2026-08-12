# HANDOFF — HousouApp（放送室）

## 現況
v1.6 / versionCode 9。Kotlin、UIは全てコード生成（XMLレイアウトなし）。
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
| `Trend.kt` | 観測値の時系列蓄積と故障予兆検知（最小二乗法の傾き） |
| `Disaster.kt` | 災害シナリオ定義（6種） |
| `Routing.kt` | 音声出力先の列挙・選択（内蔵/有線/BT/USB） |
| `Report.kt` | 日報の集計と保存（直近30日） |
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

## v1.2 で追加した約束

- **建物スコープは `Targeting.scope`**（空文字＝すべて）。`Registry.scoped()` / `floors()` / `groups()` はこれを見る。新しい絞り込みを足すときも `Targeting.resolve()` に集約する。
- **災害放送は `ConsoleService.startDisaster()` が唯一の入口**。UIも外部トリガーもここを呼ぶ。停止は `disasterOn = false` を立てるだけで、後片付けは `finishDisaster()` が担当する。
- **予兆判定は `Trend.omens()`**。現在値の評価は `Diag`、時間変化は `Trend` と役割を分ける。サンプリングは `ConsoleService` のポーリングから5分間隔で呼ぶ。
- **外部トリガーは必ずPIN照合**。`handleTrigger()` の先頭で弾き、失敗もログに残す。

## v1.3 で追加した約束

- **出力先は `Audio.Player.routeCtx` / `routeMode` に渡す**。新しい再生経路を足したら、そこにも同じ2つを渡すこと。渡し忘れると自動選択に戻る（無音にはならない）。
- **遠隔端末の登録は端末発**。`TerminalService.startRemoteRegister()` が10秒ごとにTCPで送り、`ConsoleService.startRegServer()` が受けて `Registry.upsert()` する。台帳に載った後の制御は通常経路と同じで、特別扱いしない。
- **CSVはUTF-8 BOM付き**。Excelでの文字化けを避けるため。列を増やすときは `exportCsv()` のヘッダ行も直す。

## v1.4 で追加した約束

- **予兆判定は2段構え**。`Trend.baseline()` が20サンプル以上なら平常値からの逸脱、未満なら固定閾値。新しい指標を足すときも同じ形にする（学習前でも何かは検知できる状態を保つ）。
- **平常値は中央値と四分位範囲**。平均と標準偏差にしない。放送中のスパイクや一時的な圏外で平常値が汚れるため。
- **日報の生成口は `ConsoleService.makeReport(notify)` 一つ**。自動生成もUIの手動生成もここを通す。集計ロジックは `Report.build()` に閉じる。
- **一括操作は `bulkSend()` 経由**。対象は放送タブの `targetSpec` と共有し、専用の対象選択を作らない（対象の概念を二重に持たない）。

## v1.5 で追加した約束

- **字幕の消去は必ずタイムアウトで担保する**。`showCaption()` は `captionSeq` で世代管理し、文字数からの推定時間で必ず消す。TTSの完了通知（UtteranceProgressListener）は端末によって来ないため、それだけに頼らない。
- **端末の全画面表示は優先順位を固定**。警報 > 字幕 > 放送中 > 待機。`TerminalUi.render()` の先頭で早期returnする順序がそのまま優先度なので、新しい全画面状態を足すときはこの並びに入れる。
- **時報はスケジューラの別枝**。`store.schedules()` には入れない（ユーザーの予約一覧を時報で埋めないため）。発火判定は `lastSignal` のスタンプ比較で二重発火を防ぐ。

## v1.5.1 の修正

- PTT放送中に通話を開始するとマイクを二重取得する競合 → 通話開始側でも `Mixer.micActive` を弾く（両方向の排他になった）
- 8kHz受信中に出力先を変更すると16kHzで再生再開され倍速になる → `playRate` を保持して同じレートで再開
- 災害放送終了後に端末音量が100%のまま残る → 開始時に控えた音量へ復元（元が0や100の端末は触らない）
- 対象のないログ行がCSV上で「全館」と表示される → 空欄のまま出力

## v1.6 で追加した約束

- **保留音は端末側で生成**。コンソールからストリームしない（保留のためにMixerの宛先管理を複雑にしない）。`Audio.holdTone()` + 端末内ループ再生。
- **転送は「切って張り直す」**。旧相手に `talk_stop` → 新相手に `talk_start`。中間状態を持たない。接続失敗時は通話ごと終了してユーザーに知らせる（半端な状態で残さない）。
- **キオスクのON/OFFは `store.kioskEnabled` が正**。ピン留めの実行はActivityにしかできないため、遠隔変更はフラグ＋`kioskChanged` で通知し、前面の `TerminalUi.tick()` が適用する。解除は端末側PIN必須（遠隔OFFはPIN不要＝コンソールは信頼済みという整理）。
- **マイク排他は3すくみ**（PTT/Mixer・通話・保留）。現状はUI側のガードで守っている。次に音声系を触るならMicOwner集約を先にやること（v1.5.1の教訓）。

## 次にやるなら

1. **多言語放送** — 字幕の土台ができたので、読み上げの後に別locale で繰り返す＋字幕も併記が作りやすい
2. **日報の自動送信** — 現在は通知＋手動共有。Gmail連携やIFTTT webhookで自動送付まで繋げられる
3. **週報・月報** — `Report.build()` の期間指定を広げるだけで作れる（dayOffset を from/to に一般化）
4. **予兆の抑制設定** — 端末ごとに「この指標は無視」を持たせる。誤検知が続く端末が出てきたら必要になる
5. **SIP/IP電話連携** — 実装量が大きいわりに得るものが少ない。外線が本当に要るまで着手しない方がよい

## 既知の注意点

- AP隔離（クライアント分離）がONのWi-Fiでは端末間通信ができず自動検出も失敗する
- 端末のTTS日本語データ未導入だと読み上げが無音になる
- スケジュールはコンソール常駐が前提（端末単独では発火しない）
- BGM/音声ファイルもコンソールが送出元。コンソールのプロセスが落ちると止まる
- QR読み取りにはカメラ権限が要る。初回は `ScanActivity` 内で要求する
- 長尺音源は取り込み時に全デコードするため、15分でクリップされる（`Decoder.MAX_SECONDS`）
- 予兆判定は最低6サンプル（30分）必要。それ未満は蓄積中と表示する
- 外部トリガーはHTTP平文。LAN内前提で、インターネットに露出させない
- 遠隔登録も平文。VPNの内側で使う前提。生インターネットには出さない
- 出力先の固定はAudioTrack単位。TTSには効かない
- 平常値の学習には20サンプル（約100分の常時稼働）が必要
- 日報は `store.reports()` に保存。1件あたり数KBなので30日で上限にした
- 字幕中は端末UIの更新間隔を500msに詰めている。待機時は2秒
- 時報の「いま試す」は実際に放送が出る。動作確認は対象を絞ってから行う
