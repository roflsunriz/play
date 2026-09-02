# Play

Playは、プレイリスト、アルバム、楽曲を表示・検索するアプリです。

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
- [検証手順](verification.md)
- [更新手順](how-to-update.md)
