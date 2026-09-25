# Play

Playは、ライブラリ、プレイリスト、楽曲、検索、再生を扱うミュージックプレイヤーです。

Android 7.0以降で利用できます。

## ダウンロード

[リリース](https://github.com/roflsunriz/play/releases)

アプリ内のハンバーガーメニュー「GitHubからダウンロード」からも最新のリリースを開けます。

## 使い方

1. Playを起動し、「ログイン」を押す。
2. この端末で開くブラウザーから通常どおりログインし、Playへ戻る。
3. 楽曲を選んで再生する。ログイン状態は保存される。

以前のログインで再生できない場合は、設定メニューの「ログインし直す」から認可を更新できる。成功するまで現在のログインは保持される。

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
- [フェード・Automix・音量調整](docs/playback-transitions.md)
- [同期歌詞](docs/lyrics.md)
- [アーティストページ](docs/artist-page.md)
- [更新手順](how-to-update.md)

## 依存更新の自動処理

Dependabot は対象の依存関係を毎週確認します。patch／minor 更新は PR のチェック（CI）が成功した後に自動で squash merge されます。CI の失敗ジョブは 1 回だけ再実行します。再失敗した PR は残して手動で修正します。major 更新は手動で確認します。
