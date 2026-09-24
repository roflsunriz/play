package io.github.playmusic.data.auth

object AppConstants {
    /** 公式AndroidクライアントのID（client tokenとLogin5で使用）。値はサーバーが要求するため変更しない。 */
    const val CLIENT_ID = "9a8d2f0ce77a4e248bb71fefcb557637"

    /** 公式アプリが報告する版（現行APKの版へ追随）。 */
    const val CLIENT_VERSION = "9.1.82.1596"

    /**
     * 公式Androidアプリが使うUser-Agent形式。
     * 機種は取得時の端末で固定しており、他端末ではBuild.MODELを使うこと。
     * 値自体はサーバーが検証するため変更しない。
     */
    const val USER_AGENT = "Spotify/9.1.82.1596 Android/36 (SH-R80P)"
}
