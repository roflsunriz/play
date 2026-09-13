# 更新手順

## 前提

- `COMMON-AGENTS.md`と`AGENTS.md`を全文確認し、`git status --short --branch`で既存差分を確認する。
- JDK 17以上と、ビルド設定が指定するAndroid SDKを用意する。バージョンの正本は`app/build.gradle.kts`と`gradle/libs.versions.toml`。
- 新規認証は通常のブラウザーログインと自動コールバック、保存一覧はネイティブAPI、作品の詳細と検索はカタログAPIを使う。[通信の根拠と記録](docs/api-contracts.md)を先に確認する。再生にペアリングを要求する方式へ戻さない。

## 修正と検証

1. 依存関係を更新する場合は、公式リリースの変更点・修正版・互換条件を確認する。
2. APIを変更する場合は、実応答または対象APKのprotobuf定義と照合する。未知のフィールド番号や国・契約種別を固定値として推測で埋め込まない。
3. 参照クライアントのバージョン・インストール条件・認証方式を記録してから通信を照合する。通常版と分離した診断コピーを混同せず、許可された範囲だけを扱う。解析用APKはGit管理外の`service-apks/`へ置く。
4. 通信原本はGit管理外の`captures/`に置く。検索の検証データは`tools/import-search-capture.py`で抽出し、個人情報を確認してからテストへ追加する。
5. 修正箇所のテストを実行し、その後で次を実行する。

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

WindowsでSDK・コンパイラの共有キャッシュにAccessDeniedExceptionが出る場合は、同じコマンドを昇格したPowerShellで再実行する。JDKを切り替える場合は`JAVA_HOME`を明示する。

6. 依存関係を変更した場合に限り、解決する全プラットフォームのアーティファクトについて検証メタデータを更新する。

```powershell
.\gradlew.bat --write-verification-metadata sha256 testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
.\gradlew.bat --no-configuration-cache --write-verification-metadata sha256 -I tools/verify-platform-tools.init.gradle.kts verifyPlatformTools
```

2つ目のコマンドは生成済みメタデータから実際のAAPT2バージョンを読み、Windows・Linux・macOS用の検証値をGradleに生成させる。未使用の旧依存を除く必要がある場合は元のメタデータを退避してから全体を再生成し、共通するアーティファクトのハッシュが変わっていないことを比較する。`gradle/verification-metadata.xml`を手編集しない。

親POMやBOMの検証値は、既存の依存キャッシュがあると生成対象から抜けることがある。CIだけで不足する場合は、空の検証専用ディレクトリを`-g`へ指定し、`--no-configuration-cache --write-verification-metadata sha256`で全ビルドと検証を実行する。同じ専用homeで`verifyPlatformTools`も実行し、生成前後で既存のハッシュが変わっていないこと、削除されていないこと、新しい値の取得元が正しいことを確認する。

7. OSV-Scannerで依存関係を監査する。依存情報の外部送信を伴う照会が許可されない環境では、公開DBをダウンロードしてローカルで照合する。DBの更新日時も記録する。既存の例外と緩和策は`gradle/osv-scanner.toml`および`SECURITY.md`を確認する。

```powershell
osv-scanner scan source --lockfile gradle/verification-metadata.xml --config gradle/osv-scanner.toml --offline-vulnerabilities --download-offline-databases
```
8. [検証手順](verification.md)に従い、画面操作、ログイン、ライブラリ、再起動後の認証保持を確認する。実アカウントの検証は対象端末を明示して実行する。

実音源の再生状態と操作の確認は、ログイン済みの許可された端末で `LivePlaybackTest` に `livePlayback=true` を指定する。先頭から終端まで位置を確認する場合は `fullTrack=true` も指定し、終了まで再インストールや別のinstrumentationを実行しない。このテストは音量を一時的に0にするため、音声が正常に続くことの証明にはならない。

音声の無音化は `AudioOutputTest` に `liveAudio=true` を指定して確認する。実機から音を出し、既知の検証曲の16〜30秒区間に音声が含まれるかを測定する。`fullTrack=true`も指定すると途中でホーム画面へ移し、曲の終端・中盤・終盤の音声も確認する。音声・鍵・認証情報を記録せず、端末全体の音量を変更しない。再生位置だけの成功判定へ戻さない。

ログインの自動復帰を変更した場合は `BrowserReturnTest` を `externalBrowserProbe=true` で確認する。

プレイリスト編集は、許可された実アカウントで`PlaylistAccountTest`に`livePlaylists=true`を指定して検証する。テストが作る非公開リストだけを変更し、名前・説明・画像の実取得、収録曲、削除、既存一覧の維持まで確認する。前回の検証用URIがrootlistに残る場合は新規作成を停止するため、対象を確認して削除してから再実行する。画面は分離AVDで`PlaylistEditorScreenTest`、`PlaylistArtworkTest`、`PlaylistViewModelTest`を実行し、キーボードが表示された低い横画面とRTLも確認する。

一覧・検索・再生画面は分離AVDで`HomeScreenTest`、`ContentDetailScreenTest`、`ExpandedPlayerScreenTest`、`LibraryBrowsingTest`を実行する。日本語の縦画面、低い英語横画面、アラビア語の狭幅で表示と操作を確認する。遅延一覧で未表示の項目へ移動するときは、一覧の`performScrollToKey`を使ってから完全可視性を検査する。

実アカウントでの画面連携は`LiveBrowsingScreenTest`へ`liveBrowsing=true`を指定する。ライブラリと検索を読み取り、既存曲を1曲再生・一時停止して拡大プレイヤーまで検査する。既存プレイリストの変更は行わない。`LibraryAccountTest#searchReturnsContent`では英語、日本語、短い検索語を確認する。

一覧と検索結果はアカウント別のメモリに保持し、プレイリスト一覧は専用SQLiteにも保存する。詳細の取得全体は同時2件、保持は24件・合計6000項目まで。先読みはスクロール停止から350ms後、可視範囲と直後の最大4件を同時1件で取得する。スクロール再開時は待機分だけ取り消し、先読み完了で一覧を再構築しない。手動更新、プレイリスト変更、ログアウトの無効化を変更する場合は、`AccountMemoryCacheTest`と`LibraryBrowsingTest`で通信の重複、古い応答、失敗時の再試行、アカウント分離も確認する。

スクロールは`DetailPrefetcherTest`、`ViewportPrefetchTest`、`LibraryBrowsingTest`で、低速ドラッグ中の取得抑制、待機範囲の切り替え、画面スレッドで認証情報を読まないことを検査する。実機計測は対象端末を明示して次を実行する。`LiveScrollPerformanceTest`は実時間の`ActivityScenario`と`Window.FrameMetrics`を使い、一覧内で1.8秒のドラッグを往復6回行う。外部ジェスチャー中に描画時計が止まる`ComposeTestRule`は性能測定に使用しない。修正前後それぞれプロセスを再起動し、端末・一覧・表示設定・画像キャッシュ条件をそろえて比較する。

```powershell
adb -s <許可された端末ID> shell am force-stop io.github.playmusic
adb -s <許可された端末ID> shell am instrument -w -r -e liveScroll true -e scrollPhase after -e class io.github.playmusic.LiveScrollPerformanceTest io.github.playmusic.test/androidx.test.runner.AndroidJUnitRunner
adb -s <許可された端末ID> exec-out run-as io.github.playmusic cat files/scroll-metrics/after.json
```

記録はフレーム時間と座標だけで、認証情報やライブラリ内容を含まない。`scrollPhase`は英小文字・数字・ハイフンだけを使う。受入上限を固定する場合は`-e maxScrollP95Ms <ミリ秒>`を追加する。計測対象画面だけを点灯状態に保ち、終了時に解除する。

連続ドラッグだけでなく、`-e scrollGestureMs 220 -e scrollPauseMs 900 -e scrollRepetitions 2`で高速フリックと停止を12回繰り返す。`scrollSection`は`playlists`・`albums`・`tracks`から選ぶ。プレイリストやアルバムの収録曲を測る場合は、最初の画面に見えている非空の作品の位置を`-e scrollOpenIndex <0始まりの位置>`で指定する。画面の位置や表示言語を固定した文字列で推測せず、実際のアクセシビリティ階層から対象と可視領域を取得する。Composeの仮想ノードは`findAccessibilityNodeInfosByViewId`だけでは見つからない場合があるため、`FLAG_REPORT_VIEW_IDS`を有効にした計測器が子ノードを走査する。終了時に元のフラグへ戻す。

先読み本体だけでなく、他タブの先読みを開始する入口と、取得済み詳細を開く入口も`LibraryBrowsingTest`で検査する。同期のキャッシュ確認によって認証情報が画面スレッドで読まれたり、キャッシュの再利用が追加通信に変わったりしないことを確認する。

ディスク保存は分離AVDの`io.github.playmusic.data.cache.PlaylistDiskCacheTest`と`PlaylistStartupCacheTest`で、再起動・差分行だけの保存・空一覧と未取得の区別・SQL失敗のrollback・破損復旧・世代照合・空/不完全な応答の拒否を検証する。実アカウントでは`LivePlaylistDiskCacheTest`へ`livePlaylistCache=true`を渡し、実際の一覧を保存してから再構築したコンテナの同期APIだけを遮断し、保存内容を先に表示できることを確認する。端末のWi-Fiや通常版のデータは変更しない。合成アカウントのテストはSharedPreferencesだけでなく`noBackupFilesDir`も専用ディレクトリへ分ける。

作成者の表示名は`UserProfileClientTest`でアカウントIDとの区別・返却URI照合・取得不能と通信失敗の違いを確認する。実機では`LibraryAccountTest#playlistOwnersUseTheirPublicProfileNames`を`liveAccount=true`で実行する。画面の検索欄は3タブで同じ部品を使い、`HomeScreenTest`と`LibraryBrowsingTest`で見た目・メタデータ検索・並び替え・タブごとの保持を検証する。

9. README、通信・解析記録、検証結果、CHANGELOGを更新し、日本語Conventional Commits形式でコミットする。

## 許可済み端末の通信確認

参照アプリがユーザーCAを信頼し、所有者が通信確認を許可した検証端末でだけ実行する。mitmproxyが必要。元のHTTP proxyを記録してから一時設定し、指定時間の終了時に復元する。

```powershell
$captureDevice = Read-Host '通信確認を許可した端末ID'
pwsh -File tools/capture-library-traffic.ps1 -Device $captureDevice -Seconds 60
```

記録先は`captures/`配下に表示される。ライブラリと選択した再生関連の通信を対象とし、認証・Cookieヘッダーを保存前に除去する。ライセンス通信は本文を保存せず、要求・応答のサイズとステータスだけを残す。ほかの本文やURLには私的なライブラリ情報が含まれるため原本を公開しない。`tools/inspect-library-capture.py`で構造・ステータスだけを抽出し、個人情報を確認してから文書へ取り込む。保存内容の検査は`python tools/test-capture-tools.py`で行う。

親プロセスの強制終了では復元処理が動かない場合がある。通常は指定時間の終了を待つ。異常終了した場合は、その記録先の`proxy-state.json`を読み、対象端末・元のproxy・使用ポートを確認する。現在のproxyが記録した検証用設定と一致することを確かめてから元の値へ戻す。元の値が`null`または空なら設定を削除する。`adb reverse --remove`はその記録にあるポートだけを指定し、他の転送を消さない。保存PIDを使ってプロセスを止める場合も、起動時刻とコマンドラインがこの記録ツールのものか確認する。最後に`adb shell settings get global http_proxy`と`adb reverse --list`で復元を確認する。

## 調査用の作業ファイルの整理

製品へ取り込む修正は対応するテストとともにコミットする。調査終了後の試作を通常ビルドの設定やDebug Manifestへ残さず、現行の製品経路を検査するテストを維持する。通信原本、認証情報、署名鍵、APKや依存キャッシュをソースと一緒にコミットしない。

2026-09-13には、参照版の診断パッチ・通信受信機・認証比較の未追跡26ファイルを整理した。削除前に追跡済み4ファイルの変更前後も含めてローカルの`session/archives/`へ保管し、全30原本のバイト一致とSHA-256を確認した。これは完了した調査の保存であり、実装途中の製品機能の退避ではない。保管先とハッシュは同日のsessionメモに記録している。

過去の調査を再開する場合は、アーカイブを別の空ディレクトリへ展開し、`manifest.json`の元コミット・各ファイルのハッシュと`changes.patch`を確認する。既存ファイルへ一括上書きせず、必要な箇所だけを現在の認証・署名・テスト構成と照合して取り込む。Gitのignoreやstashだけで未完成の実装を隠して整理済みとは扱わない。

## CI・配布

- Linuxでは`gradlew`の実行属性が必要。`git ls-files --stage gradlew`のmodeが`100755`であることを確認する。
- `app/build.gradle.kts`のversionName/versionCodeと、CHANGELOGの対応するバージョンをそろえる。mainのCIが成功したコミットに`v<versionName>`タグを作成してプッシュする。
- `.github/workflows/release.yml`はタグとAPKのバージョンを照合し、JVM・lint・最適化したreleaseビルドを行う。その後、署名済みAPKとSHA-256ファイルを生成し、抽出した変更履歴とともにGitHub releaseへ公開する。成功したrunにも`play-v<versionName>`成果物を保持する。
- 署名には、所有者が登録先を明示承認した同リポジトリの`PLAY_KEYSTORE_BASE64`、`PLAY_STORE_PASSWORD`、`PLAY_KEY_ALIAS`、`PLAY_KEY_PASSWORD` Secretsを使う。署名ステップだけに渡し、一時鍵ファイルはfinallyで削除する。登録時は秘密値を標準入力へ改行を付けず渡し、引数・ログへ出さない。
- CIとローカル検証は共通の`tools/sign-release.ps1`を使用する。対象ID、バージョン、debuggableでないこと、署名証明書が期待する公開版の識別子であることを確認し、署名検証とSHA-256ファイル生成まで行う。署名鍵を更新する場合はスクリプトの期待値と`SECURITY.md`を同じ変更で更新し、既存インストールからの移行も検証する。
- CIや実機の未実施項目を成功と記録しない。サービス側のエラーはHTTPの結果とメタデータ内の結果を分けて記録する。

ローカル署名にはBuild Tools 37.0.0とJDK 17を使う。`PLAY_STORE_PASSWORD`と`PLAY_KEY_PASSWORD`は実行プロセスの環境変数に設定し、コマンド引数やログへ展開しない。鍵は`.signing/play-release.p12`、aliasは`play-release`。このWindows環境のパスワードは`.signing/release-password.dpapi`に暗号化して保持している。復号するときはファイル末尾の改行を`Trim()`で除き、同じWindowsユーザーの`ConvertTo-SecureString`を使う。`.signing/`はGit管理外とし、鍵と復旧可能なパスワードを安全に保管する。

```powershell
.\tools\sign-release.ps1 -Apk <CIから取得したunsigned APK> -Version 0.2.0 `
  -KeyStore .signing/play-release.p12 -KeyAlias play-release `
  -Output build/outputs/play-0.2.0.apk -AndroidSdk $env:ANDROID_HOME
```

パスとバージョンは対象リリースに合わせる。署名証明書SHA-256を`SECURITY.md`と照合し、`apksigner verify --verbose --print-certs`の成功も確認する。配布は署名済みAPKと隣の`.sha256`だけとする。公開処理だけを手動で復旧する場合も、検証済みrunのhead SHAとタグを確認し、`gh release create`の`--verify-tag`と`--notes-file`で同じ変更履歴を使用する。公開後はGitHubからAPKを取得してハッシュと署名をもう一度照合し、実機へ入っている最終APKも一致確認する。GitHubへプッシュする前に依存関係の脆弱性監査を再実行する。

既存開発版の実機は、初回だけローカルの署名履歴による移行が必要。`tools/sign-release.ps1`へ`-Lineage .signing/legacy-to-release.lineage`を付けるとAndroid 9以降用の移行APKを作る。このAPKはv3署名専用であり、一般配布のAPKとして扱わない。分離AVDの`ReleaseUpgradeFixtureTest`（`prepareReleaseUpgrade=true`、ranchu限定）で暗号化ログイン・保存一覧・5スロットを準備し、移行APK→通常リリースAPKの順に`install -r`して画面から保持を確認する。実機で署名不一致になっても認証やデータを消して対処しない。共用の開発秘密鍵を外部へ送らない。

音質設定の変更では、`AudioEffectsScreenTest`を日本語縦画面、英語800×360、アラビア語960×1800で実行する。30番目までの調整、5スロットの保存・呼び出し・上書き・名前変更・削除、IMEと保存失敗を検査する。`AudioEffectsRouteTest`は実際のメニュー接続と、画面を戻ったときの一覧位置保持を検査する。保存・DSP・実音源の検証方法は[音質設定の設計](docs/audio-effects.md)を参照する。

## 復旧

不具合のある更新は対象コミットをrevertし、既存の認証情報を保ったまま以前のAPKへ戻す。暗号化セッションの形式を変更するときはスキーマ番号と移行・破損時の処理を同時に検証する。外部APIが変わった場合、失敗を空一覧へ置き換えて隠さず、利用者へ取得失敗を示す。

セッション形式3は旧形式2を読み込んだ際に暗号化して移行する。形式3を未対応の古いAPKへ戻すと保存認証を読めないため、再ログインが必要になる。旧APKへの切り戻しで認証状態を保持できるとは扱わない。本人がブラウザー認証中の端末には再インストール・強制終了・instrumentationを実行せず、完了または期限切れを確認する。
