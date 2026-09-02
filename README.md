# Play

Playは、Spotifyのプレイリスト、アルバム、楽曲を表示・検索し、Spotify Connectのアクティブな再生デバイスをAndroidから操作する非商用アプリです。

## 使用開始

1. [Spotify for Developers Dashboard](https://developer.spotify.com/dashboard) でWeb APIを使用するアプリを作成する。
2. Redirect URIへ `http://127.0.0.1` を登録する。ポート番号は付けない。
3. アプリを起動し、Dashboardに表示されるClient IDを入力する。
4. 「Spotifyでログイン」から権限を許可する。
5. Spotify公式アプリなどで再生デバイスをアクティブにしてから、Playでコンテンツを選ぶ。

再生操作にはSpotify Premiumが必要です。2026年のDevelopment Modeでは、アプリ所有者のPremium契約と利用者数などにSpotify側の制限があります。Client Secretは入力・保存しません。

## 機能

- PKCEログインとAndroid Keystoreによるログイン状態の暗号化保持
- プレイリスト、保存アルバム、保存楽曲の表示
- プレイリスト、アルバム、楽曲の横断検索
- 再生、一時停止、前、次、シーク、シャッフル、1曲／コンテキストリピート
- すべての主要操作のハプティクス
- 最大128 MiBのLRUアートワークキャッシュ
- 日本語、英語、中国語、ヒンディー語、スペイン語、フランス語、アラビア語、ポルトガル語、ベンガル語、ロシア語、ウルドゥー語のUIとRTLレイアウト

Spotify Web APIは音源データを提供せず、Spotifyコンテンツの独自ダウンロードもポリシーで禁止されています。そのため音源のバッファ・オフラインキャッシュはSpotify公式クライアントが管理し、Playは音源を保存しません。

## ビルド

前提:

- JDK 17以上
- Android SDK Platform 37 / Build Tools 37.0.0

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

生成APKは `app/build/outputs/apk/debug/app-debug.apk` です。

## 設計資料

- [Spotify APK解析記録](docs/spotify-apk-analysis.md)
- [検証手順](verification.md)
- [更新手順](how-to-update.md)

## 注意事項

SpotifyおよびSpotifyロゴはSpotify ABの商標です。本プロジェクトはSpotify ABによる承認・提携を示すものではありません。公開や配布の前にSpotify Developer Policy、Design Guidelines、利用規約、アプリ審査要件を確認してください。
