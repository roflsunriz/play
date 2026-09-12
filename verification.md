# 検証手順と結果

## 2026-09-12の結果

| 検証 | 結果 |
| --- | --- |
| JVM単体・通信結合テスト | 52件成功、失敗・スキップなし |
| Android lint、Debug APK・検証APK生成 | 成功 |
| 分離したAVDの画面・保存・キャッシュテスト | 13件成功 |
| 日本語横画面・アラビア語狭幅 | 各7件成功。画像とタイトルの表示範囲も確認 |
| 通信記録ツール | 3件成功。認証・Cookieの除去、原通信の維持、fixture抽出、破損応答の拒否を確認 |
| 実機のパスワード・確認コード認証 | ユーザー操作でログイン成功 |
| 実機の保存認証による更新 | 成功。更新後のセッション保存と保存認証情報の保持を確認 |
| 実機のプレイリスト | 67件取得。名前が空の1件も「無題のプレイリスト」として保持 |
| 実機の保存アルバム・曲 | 未解決。保存IDは全8ページ取得できるが、詳細応答内のステータス502で失敗 |
| 実機の検索 | 未解決。「Nirvana」はHTTP 200でもentityが0件。「Beatles」はプレイリストを返すが、曲・アルバムの成功は未確認 |
| 実アカウント自動テスト | 5件中2件成功、3件失敗。アルバム・曲・検索の失敗する期待値を維持 |
| OSV依存関係監査 | オンラインで435パッケージを検査し、メタデータのみの旧版に対する既存例外適用後の検出0件。Kotlinの包括的な脆弱性除外は削除 |
| ホスト側CI | 既存失敗の実行属性を修正。プッシュ・新しいCI実行は未実施 |

実機はAndroid 16 / API 36、AVDはPixel 9 Pro。実アカウントのライブラリ件数は検証時点のもの。HTTP成功と、protobuf内の個別処理成功を区別する。元のアプリでも同条件でメタデータ内の502を観測したが、画面上は保存済み内容を表示できる。原因がサービス全体の障害だとは断定しない。通信の根拠は[確認記録](docs/api-contracts.md)を参照する。

画面確認は日本語1280×2856、日本語1920×1080、アラビア語960×1800、density 480で実施した。横画面で一覧タイトルが切れる問題を検出し、横幅と高さに応じて再生操作を1行へまとめた後、同じテストと画像を確認した。確認コードの下側のボタンはスクロールして操作できる。

## ビルド・単体検証

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python tools/test-capture-tools.py
```

検証する内容:

- protobufの未知フィールド、切断、長さ、整数境界、グループの処理
- Login5のパスワード・保存認証・確認コード要求、致命的エラーでの停止、不完全な成功応答の拒否
- HashCashのビット条件・入力検証・中断、クライアントトークンの期限と共有
- プレイリストの名前と属性の対応、無題、ページ送り、コレクションの削除と重複処理
- 実検索fixture、国・契約属性、メタデータの画像と長さ、失敗の伝播、HTTP 401後の更新

レポートは`app/build/reports/tests/testDebugUnitTest/`と`app/build/reports/lint-results-debug.html`。Gradleは`gradle/verification-metadata.xml`でアーティファクトのSHA-256を検証する。OSV例外の理由・期限は`gradle/osv-scanner.toml`と[セキュリティ方針](SECURITY.md)を確認する。

Pythonの記録ツール検証にはmitmproxyが必要。Kotlinは安定修正版2.4.20へ更新し、既存アーティファクトのハッシュが変わっていないことと、実際のビルド依存が修正版へ解決されることを確認した。lintにはCompose BOMとCoilの新しい版を案内する情報通知が残るが、非推奨APIの使用警告やエラーはない。

## 端末を限定した画面検証

実アカウントを使わない検証には分離したAVDを使う。ログイン画面のテストは未ログイン状態を前提とするため、ログイン済みの実機を含む全端末へ一括実行しない。

```powershell
adb devices
$testDevice = Read-Host '検証用AVDの端末ID'
adb -s $testDevice install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s $testDevice install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s $testDevice shell am instrument -w -e class io.github.playmusic.HomeScreenTest,io.github.playmusic.SetupScreenTest,io.github.playmusic.SessionVerificationScreenTest,io.github.playmusic.data.cache.TrackCacheTest,io.github.playmusic.data.security.SecureSessionStoreTest io.github.playmusic.test/androidx.test.runner.AndroidJUnitRunner
```

画面テストは検索入力、タブ、更新、メニュー、ログアウト、無題項目、再生操作の通知、確認コードの入力・キャンセル・復帰、エラーの種類と閉じる操作を確認する。再生操作の通知テストは、実デバイスで音が鳴ることの証明ではない。保存テストは独立したpreferencesへ架空セッションを書き、再読込、破損時の除去、端末IDの保持を確認する。

画面画像を残す場合はinstrumentation引数へ`-e screenshotPrefix ja-portrait`などを追加する。画像は対象アプリ内の`files/ui-verification/`へ保存する。架空データの画面だけを記録し、実ユーザーの認証画面やライブラリを公開しない。

## 実アカウントの確認

本人が使用を許可したアカウントと、保存プレイリスト・アルバム・曲がある端末を使う。パスワード・確認コードはPlayへ直接入力し、コマンドやログへ含めない。アプリデータを削除しない。

```powershell
adb devices
$liveDevice = Read-Host 'ログイン済みの検証端末ID'
adb -s $liveDevice shell am instrument -w -e class io.github.playmusic.LibraryAccountTest -e liveAccount true io.github.playmusic.test/androidx.test.runner.AndroidJUnitRunner
```

このテストは実際に認証更新・一覧取得・検索を行う。`liveAccount=true`なしでは実行しない。認証更新とプレイリストが通っても、アルバム・曲・検索の失敗を成功扱いしない。

再起動時の保持は、Playを強制終了し、再起動してログイン画面へ戻らず一覧を取得できることを確認する。アプリのプロセス再起動と保存認証の強制更新は確認済み。端末OSの再起動は今回は実行していない。

## 残る検証と再開条件

- アルバム・曲: 元のアプリがオンラインで返す成功した詳細応答を取得できたら、同じ種類の要求・アカウント属性・応答形式を照合する。元のアプリのキャッシュ表示だけを通信成功の根拠にしない。
- 検索: 新しい成功応答と要求を取得し、既知の検索語で曲・アルバム・プレイリストを確認する。現在は応答本文自体に曲・アルバムがないため、fixtureのデコード成功だけで解決としない。
- フルサイズ再生、再生状態の取得、実デバイスの再生・シーク・リピート・シャッフルは未完成。音源キャッシュの単体検証は通るが、現在のメタデータ経路はプレビューURLを設定しておらず音源再生へ未接続。
- 触覚はOSのAPIへ接続しているが、端末設定を変えた際の体感確認は未実施。
- 監査後に公開される脆弱性、新しいホスト側CI、署名済みリリースの配布は未検証。リリース時に再確認する。
