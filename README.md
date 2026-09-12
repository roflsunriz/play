# Play

Playは、音楽サービスのライブラリを表示・検索する開発中のAndroidアプリです。

## 使い方

1. Playを起動し、サービスのユーザー名とパスワードを入力して「ログイン」を押す。
2. メールの確認コードを求められた場合は、届いたコードを入力する。
3. 「プレイリスト」「アルバム」「楽曲」を切り替えて保存一覧を開く。「検索」から作品名を検索する。
4. 一覧の取得に失敗した場合はエラーを閉じ、通信状態を確認して「更新」を押す。

認証情報は端末のKeystoreで暗号化して保存し、有効期限が近づくと保存済みの情報で再認証します。パスワードは保存しません。

2026-09-12時点では、実機でログイン・認証保持とプレイリスト67件の取得を確認しました。保存アルバム・楽曲の詳細取得は提供元の応答エラーで失敗し、検索も一部の語で結果が返りません。これらは調査中です。

フルサイズの楽曲再生と再生デバイスの制御は未完成です。確認済みの範囲と実機検証の状況は[検証手順](verification.md)に記載しています。

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
- [更新手順](how-to-update.md)
