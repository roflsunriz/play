# 検証手順と結果

## 2026-09-13の結果

Windows版を基準にした新しい認証・カタログ・端末内再生の実装を検証した。本人によるPlayのブラウザー認証が未完了のため、実楽曲のフル再生を完了とは扱わない。

| 検証 | 結果 |
| --- | --- |
| JVM単体・通信結合テスト | 59件成功、失敗・スキップなし |
| Android lint、Debug APK・検証APK生成 | 成功。エラー0件 |
| Release APK生成 | R8による縮小処理を含め成功。未署名・未公開 |
| 日本語縦画面のAVD | 合成データの32件成功。実サービスへの認証開始1件はオプトイン引数なしのため実行対象外 |
| 日本語横画面・アラビア語狭幅・英語タブレット | 各12件成功。画像と表示範囲を確認 |
| 本番再生サービスの初期化 | Media3・キャッシュ・DRM設定を使うサービスへ接続できることを確認 |
| 合成音源の端末内再生 | 一時停止、シーク、再開、前後の曲、リピート1曲・全曲、終端からの再開、シャッフル、接続中の停止を確認 |
| 音声キャッシュ | 暗号化データを想定した不透明バイト列の再利用、部分読み込み、取得失敗、容量上限と押し出しを確認 |
| 保存認証 | 更新トークンの暗号化保存、旧形式からの移行、破損回復、ログアウト・別アカウントへの切り替えと更新の競合を確認 |
| 画面と非同期処理 | 確認コード画面の復帰、詳細の再試行・戻る操作・重複曲の位置、再生進行中のシークドラッグを確認 |
| 参照Windows版 | 新規認証・更新要求、正規ユーザー名、検索・詳細・曲の一括取得・保存一覧・音声配信情報の実応答を確認 |
| Play実機 | 認証開始のHTTP 200を確認済み。待機の期限切れを確認して最新Debug APKへ更新し、起動成功 |
| 実楽曲のライセンス取得・フル再生 | 未確認。Play自身のブラウザー認証完了が必要 |
| 依存関係監査 | 公開DBを取得しローカルで475パッケージを照合。既存のメタデータ例外適用後の検出0件 |

画面確認は日本語1280×2856・density 480、日本語1920×1080・density 480、アラビア語960×1800・density 480、英語2048×1536・density 320で実施した。対象は読み取り専用で起動した分離AVD。実アカウントの端末へ未ログイン前提のテストは実行していない。

検証で発見したDRM初期化の無効値と、非同期取得が古い画面状態を上書きする競合を修正した。一時停止テストはコントローラーの即時応答だけで判断せず、音声処理と画面の位置がそれぞれ停止することを確認する。コントローラーの位置は[Media3の推定処理](https://github.com/androidx/media/blob/1.11.1/libraries/session/src/main/java/androidx/media3/session/MediaUtils.java)に従うため、両者の位置がサンプル精度で一致するという誤った前提は置かない。

オンライン照会は依存情報の外部送信を理由に自動承認で拒否された。公開DB全体だけを取得する方法へ切り替え、パッケージ情報を外部へ送らず照合した。Maven DBの取得日は2026-09-13、含まれる最新の更新記録は2026-09-11 UTC。結果は`build/qa/osv-media3-fresh-report.json`。ビルド・AVD結果と合成画面の画像は`build/qa/media3-*`と`build/qa/ui-media3/`に残す。

## 2026-09-12の結果（旧経路の記録）

この表の参照アプリの失敗は、改変・非ストア版を使っていた時点の観測。同日夜にユーザーがGoogle Play通常版へ入れ直したため、通常版を基準に切り分け直している。アプリの改変・インストール元の差が混ざった結果を、提供元全体の障害と解釈しない。

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

レポートは`app/build/reports/tests/testDebugUnitTest/`と`app/build/reports/lint-results-debug.html`。Gradleは`gradle/verification-metadata.xml`でアーティファクトのSHA-256を検証する。OSV例外の理由・対象版は`gradle/osv-scanner.toml`と[セキュリティ方針](SECURITY.md)を確認する。

Pythonの記録ツール検証にはmitmproxyが必要。Kotlinは安定修正版2.4.20へ更新し、既存アーティファクトのハッシュが変わっていないことと、実際のビルド依存が修正版へ解決されることを確認した。製品コードのlintにはCompose BOMの新しい版の案内だけが残る。中断中の診断コードを含む作業ツリーでは、Debug専用受信機の公開設定についても警告される。受信はDUMP権限による開始操作と有効期限付きnonceで制限する。非推奨APIの警告やエラーはない。

## 端末を限定した画面検証

実アカウントを使わない検証には分離したAVDを使う。ログイン画面のテストは未ログイン状態を前提とするため、ログイン済みの実機を含む全端末へ一括実行しない。

```powershell
adb devices
$testDevice = Read-Host '検証用AVDの端末ID'
adb -s $testDevice install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s $testDevice install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s $testDevice shell am instrument -w -e class io.github.playmusic.HomeScreenTest,io.github.playmusic.SetupScreenTest,io.github.playmusic.SessionVerificationScreenTest,io.github.playmusic.BrowserLoginScreenTest,io.github.playmusic.ContentDetailScreenTest,io.github.playmusic.PlaybackUiTest,io.github.playmusic.CatalogJsonTest,io.github.playmusic.DeviceAuthorizationClientTest,io.github.playmusic.LocalPlaybackTest,io.github.playmusic.data.cache.TrackCacheTest,io.github.playmusic.data.security.SecureSessionStoreTest io.github.playmusic.test/androidx.test.runner.AndroidJUnitRunner
```

画面テストは検索入力、タブ、更新、メニュー、ログアウト、無題項目、再生操作の通知、確認コードの入力・キャンセル・復帰、エラーの種類と閉じる操作を確認する。再生操作の通知テストは、実デバイスで音が鳴ることの証明ではない。保存テストは独立したpreferencesへ架空セッションを書き、再読込、破損時の除去、端末IDの保持を確認する。

画面画像を残す場合はinstrumentation引数へ`-e screenshotPrefix ja-portrait`などを追加する。画像は対象アプリ内の`files/ui-verification/`へ保存する。架空データの画面だけを記録し、実ユーザーの認証画面やライブラリを公開しない。

## 実アカウントの確認

本人が使用を許可したアカウントと、保存プレイリスト・アルバム・曲がある端末を使う。新規接続はブラウザーで本人が認証する。Playに表示された接続コード、パスワード、メールの確認コード、更新トークンをコマンドやログへ含めない。アプリデータを削除しない。認証待機中にAPKの更新やinstrumentationを実行しない。

```powershell
adb devices
$liveDevice = Read-Host 'ログイン済みの検証端末ID'
adb -s $liveDevice shell am instrument -w -e class io.github.playmusic.LibraryAccountTest -e liveAccount true io.github.playmusic.test/androidx.test.runner.AndroidJUnitRunner
```

このテストは実際に認証更新・一覧取得・検索を行う。`liveAccount=true`なしでは実行しない。認証更新とプレイリストが通っても、アルバム・曲・検索の失敗を成功扱いしない。

再起動時の保持は、Playを強制終了し、再起動してログイン画面へ戻らず一覧を取得できることを確認する。アプリのプロセス再起動と保存認証の強制更新は確認済み。端末OSの再起動は今回は実行していない。

## 残る検証と再開条件

- Play自身のブラウザー認証を完了し、保存アルバム・曲・プレイリストの一覧と詳細、既知の検索語で3種類の検索を確認する。参照Windows版の実応答をPlayの成功扱いにしない。
- Playの端末内で、実楽曲のライセンス取得、曲の長さ、1分以降へのシーク、曲の終端、前後移動、反復とランダム再生、背景再生を確認する。証明書要求のHTTP 200や合成音源テストだけでは確認済みとしない。
- 認証を完了した後でプロセスを再起動し、新しい更新トークンによる認証保持を確認する。旧方式の保持は確認済みだが、新しい実認証と端末OSの再起動は未確認。
- 触覚はOSのAPIへ接続しているが、端末設定を変えた際の体感確認は未実施。
- 監査後に公開される脆弱性、新しいホスト側CI、署名済みリリースの配布は未検証。リリース時に再確認する。
