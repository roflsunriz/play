# Play

Playは、ライブラリ、プレイリスト、楽曲、検索、再生を扱うミュージックプレイヤーです。

Android 7.0以降で利用できます。

## ダウンロード

[リリース](https://github.com/roflsunriz/play/releases)

## 使い方

1. Playを起動し、「ログイン」を押す。
2. メールアドレスとメールアドレスに届くOTPコードを入力する。
3. 楽曲を楽しむ。

## ビルド

前提:

- JDK 17以上
- Android SDK Platform 37 / Build Tools 37.0.0

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

生成APKは `app/build/outputs/apk/debug/app-debug.apk` です。

## 設計資料

- [解析記録](docs/apk-analysis.md)
- [認証・ライブラリ通信の根拠と復元した検証データ](docs/api-contracts.md)
- [検証手順](verification.md)
- [音質設定の設計](docs/audio-effects.md)
- [更新手順](how-to-update.md)
