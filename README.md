# Play

Playは、プレイリスト、アルバム、楽曲を表示・検索するアプリです。Spotifyアカウント（ユーザー名+パスワード）でログインし、アクティブなSpotifyデバイスの再生を操作できます。

## ビルド

前提:

- JDK 17以上
- Android SDK Platform 37 / Build Tools 37.0.0

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

生成APKは `app/build/outputs/apk/debug/app-debug.apk` です。

## 使い方

1. 起動してSpotifyのユーザー名とパスワードを入力し、「Spotifyでログイン」を押す。
2. プレイリスト・アルバム・楽曲が表示される。各項目をタップするとアクティブデバイスで再生する。
3. 楽曲はプレビュー音源（30秒）をアプリ内で再生でき、音源は容量上限付きLRUキャッシュへ保存される。

## 設計資料

- [解析記録](docs/apk-analysis.md)
- [検証手順](verification.md)
- [更新手順](how-to-update.md)
