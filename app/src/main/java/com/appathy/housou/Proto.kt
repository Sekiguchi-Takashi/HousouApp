package com.appathy.housou

object Proto {
    /** 端末 -> ブロードキャスト（自己アナウンス） */
    const val PORT_ANNOUNCE = 45300

    /** 端末が待ち受ける制御用TCPポート */
    const val PORT_CTRL = 45301

    /** コンソール -> 端末 の音声UDP */
    const val PORT_AUDIO_DOWN = 45302

    /** 端末 -> コンソール の音声UDP（通話用） */
    const val PORT_AUDIO_UP = 45303

    /** コンソールの外部トリガー受付（HTTP GET） */
    const val PORT_TRIGGER = 45304

    /** コンソールの遠隔端末登録受付（TCP・別サブネット/VPN用） */
    const val PORT_REG = 45305

    const val RATE_HIGH = 16000
    const val RATE_LOW = 8000

    const val APP_VER = "1.4"

    /** この時間、アナウンス/応答が無ければオフライン扱い */
    const val OFFLINE_MS = 14000L

    /** 音声フレーム長（ミリ秒） */
    const val FRAME_MS = 20

    fun frameBytes(rate: Int): Int = rate / (1000 / FRAME_MS) * 2
}
