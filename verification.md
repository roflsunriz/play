# 検証手順と結果

## 2026-09-13の結果

### 通常ログインへの変更後

- ペアリング用の認証コード・ポーリング・コード表示を廃止し、通常ログインと同一端末への自動復帰へ変更した。
- JVM61件成功。PKCEの照合、state・パス・重複パラメーターの拒否、キャンセル時のリスナー解放、更新トークンの継続利用を確認した。
- 分離AVDの実ブラウザーから合成ログイン結果を返す検証で、Playが一旦背景へ移り、追加タップなしで前面へ戻ることを確認した。`BrowserReturnTest`は`externalBrowserProbe=true`を明示して分離AVDだけで実行する。
- 通常ログインへ変更後の表示・認証・詳細・再生・キャッシュ・保存のAVD検証は32件成功した。`build/qa/normal-login-full-avd.log`。
- 日本語横画面の認証検証14件、アラビア語狭幅の画面検証6件も成功。確認コード入力直後はIMEとスクロールの配置確定を待ち、確認ボタン全体が表示されることを確認する。
- 実機のブラウザーに保存されたログイン状態を使い、パスワードやペアリングコードを入力せず、通常ログインと暗号化保存が完了した。
- 実アカウントの検証6件成功。保存アルバム39件、曲1,384件、プレイリスト67件、検索90件、認証の自動更新を確認した。`build/qa/normal-login-live-library.log`。
- 実機でログイン中のプロセス凍結を観測したため、ログイン待機だけを前景サービスで維持するよう修正した。実機の既定ブラウザーでも、12秒間背景に置いた後の合成応答から自動復帰するテストに成功した。`build/qa/normal-login-phone-browser-return.log`。
- 旧保存認証の無操作移行は未解決。AP認証・保存資格情報での再接続後のKeymasterが403、音声配信情報はHTTP 200・media空だった。記録は`build/qa/no-pairing-device-probe.log`、`no-pairing-native-device.log`、`no-pairing-shape-device.log`。この方法を有効な代替として採用していない。
- 実曲のライセンス取得とフル再生も成功。長さ301,920msの音源を先頭から終端まで再生し、90秒へのシーク、一時停止・再開を確認した。`build/qa/normal-login-live-playback.log`。
- 続けて、2曲の実音源で次・前・1曲リピート・全曲リピート・シャッフルの操作を確認した。`build/qa/normal-login-live-queue.log`。検証中はプレイヤーのゲインだけを0にし、終了時に復元した。端末全体の音量設定は変更していない。
- 実画面でも検索からアルバム詳細へ移動し、画像、タイトル、アーティスト、発売日、13曲の収録一覧を確認した。画像は非公開の`captures/play-actual-album-detail.png`。
- 再生検証後にPlayのプロセスを停止・再起動し、ログインやペアリングの画面へ戻らず保存一覧を表示できることを確認した。
- 長時間の検証後に更新認証の`invalid_grant`を観測し、更新トークンが毎回入れ替わることを確認した。保存を非同期完了待ちから書き込み完了確認へ変更し、null応答と不正な型も区別した。修正後は連続更新、プロセス再起動後の連続更新、期限切れ状態からの自動更新が成功した。`build/qa/consecutive-refresh-live.log`、`refresh-restart-and-details.log`。
- 新しい認証で、保存プレイリストの詳細から64曲中64曲のメタデータを取得できることを確認した。
- 保存完了保証を含む最終APKでも、実アカウント9件、実音源の再生操作1件、AVDの認証・保存検証15件が成功した。`build/qa/final-live-account.log`、`final-live-playback-controls.log`、`rotation-final-avd.log`。

### 切り替え前の検証記録

以下は切り替え前の状態であり、最新の確認結果は上記を参照する。

Windows版を基準にした新しい認証・カタログ・端末内再生の実装を検証した時点では、Playのブラウザー認証が未完了で、実楽曲のフル再生は未確認だった。

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
adb -s $testDevice shell am instrument -w -e class io.github.playmusic.HomeScreenTest,io.github.playmusic.SetupScreenTest,io.github.playmusic.SessionVerificationScreenTest,io.github.playmusic.BrowserLoginScreenTest,io.github.playmusic.ContentDetailScreenTest,io.github.playmusic.PlaybackUiTest,io.github.playmusic.CatalogJsonTest,io.github.playmusic.BrowserAuthorizationClientTest,io.github.playmusic.LocalPlaybackTest,io.github.playmusic.data.cache.TrackCacheTest,io.github.playmusic.data.security.SecureSessionStoreTest io.github.playmusic.test/androidx.test.runner.AndroidJUnitRunner
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

## 確認済みの到達点と検証範囲

- 通常ログイン後、保存一覧・検索・作品詳細と、端末内の実楽曲の再生・シーク・前後移動・反復を確認した。再生用のペアリング操作は不要。
- 合成音源に加え、実楽曲を最初から最後まで再生して確認した。音を聞いて評価する試聴ではなく、ミュート状態でライセンス取得・再生進行・終端を検証した。
- 端末OS全体の再起動、他機種・他アカウント・異なる契約や地域での実楽曲確認は未実施。
- 触覚はOSのAPIへ接続しているが、端末設定を変えた際の体感確認は未実施。
- 監査後に公開される脆弱性、新しいホスト側CI、署名済みリリースの配布は未検証。リリース時に再確認する。
