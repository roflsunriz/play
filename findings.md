# play の実現手段と調査知見

作業着手前にこの文書を全文読む。共通方針と目的・行動義務・禁止事項は [AGENTS.md](AGENTS.md) を参照する。

## 共通方針に対する固有の実現手段

- 対象の共通ルール: 検証のプロジェクト別ビルド・監査。
- 理由: Android/Kotlinと標準Media3/DRMを使い、ビルドだけでは実音声の継続や保存認証を判定できないため。
- 適用範囲: 既存Gradle・AVD・明示許可済み端末の検証経路。
- 代替手段・検証: JVMテスト・Android Lint・APK生成・検証メタデータとOSV監査に加え、該当変更で分離AVDや音声計測を使う。既存の端末・アカウント操作の許可範囲を拡張しない。
- 文書構成: 実現手段・環境・過去の調査知見はこの文書を正本とする。利用者向け導入・使用法はREADME、更新の入口はhow-to-update、実測結果はverification、変更意図はCHANGELOGに残す。共通方針の品質・秘密情報保護・ユーザー指示を免除しない。
- 移行基準: 2026-10-16の既定ブランチ `e5a578167ad55d8046abc9a707bd635320d8d088`。以下は出典の本文を移動したもので、過去の確認日時・適用条件・未検証事項を維持する。


<a id="agents-4"></a>

移動元: [AGENTS.md](AGENTS.md)。

## 契約
- DMCAリスクを下げるためソースコード以外に対象サービスの固有名を入れない。入っている場合は削除。
- sessionフォルダに適宜進捗を残し、後続エージェントが作業を続けやすくすること。
- sessionのファイル名は<month>-<day>-<hour>-<minutes>.mdとすること


<a id="agents-5"></a>

移動元: [AGENTS.md](AGENTS.md)。

## 調査・検証で確認した注意点

- 2026-10-02、Gradle 9.8.0のPR #20は内部ビルドクラスパスの`kotlin-reflect`・`kotlin-stdlib` 2.4.10の検証値不足でCIが失敗した。アプリのKotlin Gradle Plugin 2.4.20は更新不要でもWrapper更新時に検証メタデータ生成が必要。`--write-verification-metadata sha256`で生成し、旧ハッシュ不変と公式配布物の一致を確認する。詳細は`how-to-update.md`。
- 2026-10-15、Composeプラグイン更新のPR #23も、プラグインマーカーPOMの検証値不足でCIが失敗した。版カタログの共通Kotlin版はComposeだけでなくAGP側のKotlin Gradle Plugin解決にも影響する（`buildEnvironment`で2.2.10→2.4.21を確認）。プラグインと関連依存の検証値を生成し、監査例外の理由に記載した実行版も更新する。手順は`how-to-update.md`。

- 2026-09-15、「トリッカル」の検索は所有者ProfileのURIがpercent encoded、要求usernameがdecodedで、文字列一致が同一人物を拒否していた。URIの識別子だけを1回decodeし、`+`を空白へ変換せず照合する。別人・不正escapeは拒否を維持する。カタログだけの成功で検索画面の成功とせず、Repositoryの所有者名取得まで実機検査する。
- 同日、「インターネット」の`searchGenres`に`GenreResponseWrapper/Genre`の「お気に入りの曲」カードが含まれた。通常ジャンルとは別の現ユーザーcollectionリンクを、既存の専用お気に入りURIへ接続する。未知のURIを包括的に無視しない。型・具体値・再現検査は`CatalogJsonTest`、`MusicRepositoryCacheTest`、`docs/api-contracts.md`。
- 2026-09-15、ユーザーが未フォローのアーティスト1組だけの一時フォロー・解除を明示許可した。`ArtistFollowAccountTest`の`liveArtistFollow=true`は対象URIのchecked journalとアカウント照合を維持し、既存のフォロー・コレクションを変更しない。
- 新しい音声トランスポートは2つの標準DRM ExoPlayerを持つ`TransitionPlayer`。MediaSession.playerをExoPlayerへ直接castしない。計測はエンジン別に分け、待機中のゲイン1を再生中のゲイン比較へ混ぜない。停止直後の位置は数ms補正され得るため、rawの停止フラグと有界の位置確定を確認してから不変性を検査する。選曲置き換え後の重複`play()`は重なり有効中のエンベロープへ触れず、全音量再生中の冗長要求は再生状態の再確認だけにする。フェード反転（一時停止直後の再開）は現在の音量から戻す。同曲シークの重なりは設定のシーク時クロスフェード（初期無効）で扱う。予備デコーダーの重なりは新しいライセンス取得待ちで止まるため使わず、再生中のデコーダーで消音と同時にシークして設定秒数でフェードインする。回帰は分離AVDの`PlaybackTransitionsTest`。
- 自動曲間調整は拡張メタデータ27（対象playlist）と28（cuepoint）を使う。DEFAULT=1のmodeと、原本への両曲の所属、型・URI・cuepointの妥当性を検査する。同一曲内シークで次曲の準備を不用意に破棄しない。契約・閾値・検証は`docs/playback-transitions.md`と`docs/api-contracts.md`。
- 2026-09-15の実音源ではoutcue後の実時間が2342msしかない一方、要求する重なりは5625msだった。開始位置を捨てず実残時間へ短縮する。制御の遅れも100ms一律棄却にせず、残る区間の中で入場位置と時間を補正する。`TeeAudioProcessor`のsink.flushはEOSでも呼ばれるため、出力計測で音声が残っている窓を消さない。`MediaTimeLevelMeter`は媒体時刻・period・世代で数値窓を照合する。
- 歌詞は曲のURIで分離し、LINE_SYNCED/SYLLABLE_SYNCEDの行開始と次行開始に従う。表示場所は拡大プレイヤーの再生中の曲のみとし、再生していない曲の詳細画面には置かない。公式配信とLRCLIBをタブで切り替え、LRCLIB側は曲名・歌手・アルバム・再生時間を照合して別曲を表示しない。自動アニメーションと手動・アクセシビリティのスクロールを区別する。本文をログ・fixture・Gitへ保存せず合成文で画面を検査する。`docs/lyrics.md`。
- 詳細内の曲並びは`DetailSort`で行い、表示・再生・選択を同じ順序にそろえる。再生数・曲順はカタログ応答の`playcount`・`trackNumber`・`discNumber`、追加日はプレイリスト項目のタイムスタンプ（書込時と同じミリ秒）と保存コレクションの追加時刻（秒）を使う。指定値は`DetailSortStore`へ保存し、他種別・他画面の選択と混ぜない。

- 2026-09-15、検索入力キャンセルがOAuth更新の応答受け取りと保存の間へ入り、入れ替わった更新トークンを失う経路を確認した。更新開始後は`SessionManager`のmutex内で`NonCancellable + IO`により保存まで完了させ、アカウント世代照合を維持する。`SecureSessionStoreTest`でキャンセル後の新storeからの連続更新を検査する。サーバーで失効済みの値は復元できず、通常ログインの本人操作が必要。
- 「お気に入りの曲」の専用URIは原本rootlist内の通常プレイリストではない。Repositoryの専用factoryとTRACKコレクションへ接続し、UIだけに一度追加する。所属・削除確認は装飾後の曲一覧ではなく全ページの原本URIを使う。コレクションWriteRequestとジャンルpreviewのページ構造は`docs/api-contracts.md`、保存回帰は`SavedLibraryActionsTest`。
- 2026-09-15、ユーザーが実機で未登録の曲とアルバム各1件のお気に入り一時追加・削除を明示許可した。`SavedItemsAccountTest`へ`liveSavedItems=true`を指定し、ジャーナルを保存して既存コレクションの保持と追加分だけの削除を確認する。既存プレイリストは変更しない。
- スリープタイマーの消灯はAndroidの`force-lock`端末管理者権限と正確なアラームを使う。実機への権限付与は本人操作とし、`SleepTimerIntegrationTest`の強制消灯は`ranchu/goldfish`の分離AVDと`sleepTimer=true`に限定する。権限取消・再起動で存在しない予約を表示しない。仕様と検証は`docs/sleep-timer.md`。
- カタログのアーティスト名は`profile.name`、ジャンル画像は`image.sources`。ジャンル初回`sectionItems`は`items/totalCount`だけのプレビューであり、`pagingInfo`を要求しない。全件取得は`browseSection`をoffset=0からたどり、この経路のnextOffset=0は終端。公開型と実応答の照合結果は`CatalogNavigationTest`と`docs/api-contracts.md`。

- 2026-09-15、setup-android v4.0.1の既定`tools platform-tools`は旧`tools`が見つからずCIで失敗した。`packages: platform-tools`を通常CI・リリースCIで明示する。sdkmanagerを含むcmdline-toolsの導入と、Platform/Build Toolsの別ステップは維持する。
- 2026-09-15、一部の旧版曲の配信情報は、要求したURIと異なる再生可能版のURIをmediaのキーとして返す。`item.metadata.uri`とそのキーを照合し、`linked_from_uri`が要求URIと一致する唯一の候補を使う。先頭要素や曲名一致で代替しない。Queen Jewels全16曲で旧実装の失敗を確認。根拠と検証は`docs/api-contracts.md`、`StreamingApiClientTest`。
- 音質設定は`AppContainer.audioEffects`で端末単位に保持する。音声スレッドはimmutableな`state.value.settings`だけを読み、保存・認証・UI更新を行わない。Media3のカスタムprocessorはfloat/圧縮passthrough/offloadで迂回され得るため、30バンドEQはPCM16経路へ接続し、計測用serviceも本番processorの後へmeterを置く。設計と根拠は`docs/audio-effects.md`。
- 2026-09-13、ユーザーがプッシュとリリースを許可し、続いて送信先`roflsunriz/play`のActions SecretsへのPlay専用署名鍵・パスワード登録を明示承認した。4つの署名Secretsを登録済み。秘密値は標準入力へ改行を加えず渡し、引数・ログ・Gitへ出さない。共用debug秘密鍵は外部へ送らない。ローカル控えは`.signing/`、既存実機の移行は署名履歴とAndroid 9以降用の一度限りの移行APKで検証する。
- v0.2.0で、明示承認を得て実機のPlayを専用の公開署名へ移行済み。以降、共用debug鍵のAPKをそのまま上書きできるとは扱わない。実機で開発用APKを検証する場合は本体とテストAPKを同じ専用鍵で署名し、署名不一致を理由に本体や認証データを消さない。公開APKはdebuggableにせず、署名の識別子と手順は`SECURITY.md`と`how-to-update.md`を使う。
- ライブラリ通信はWeb APIプロキシではなくネイティブのprotobuf契約を使う。根拠・フィールド番号・記録の所在は `docs/api-contracts.md` に集約する。ヘッダーの推測追加で解決したことにしない。
- `ProtoWire.Reader` で未知の長さ付きフィールドを飛ばす際、読み取り位置への複合代入の右辺で長さを読まない。位置更新前に長さを取得する。`ProtoWireTest` が境界と未知フィールドを検証する。
- プレイリストの改訂番号はバイト列、コレクションの追加時刻は整数であり、同じアイテムパーサーを流用しない。拡張メタデータのqueryは型付きの要求で、ランダムバイト列ではない。
- プレイリストの作成要求は`OpList`のops field 1、既存リストへの変更は`ListChanges`内の`Delta`のops field 2を使う。通常Webのメソッド名だけでメッセージ型を決めない。2026-09-13、後者を作成へ送るとHTTP成功でも無名になり、実機で訂正した。画像はサイズ別URLが無い場合も属性のpicture bytesから解決する。作成から画像実取得・収録曲編集・削除までの根拠は`docs/api-contracts.md`。
- 実アカウントの書込検証は`PlaylistAccountTest`へ`livePlaylists=true`を指定し、自分で作る非公開リストだけを変更する。検証用URIのジャーナルもchecked `commit()`で保存・削除し、原本rootlistで不在を確認した場合だけ古い記録を消す。装飾時に取得不能項目が落ちる一覧を、不在確認に使わない。
- 保存済み認証情報による再認証はv3、パスワード認証はv4を使う。認証応答の本文をログへ出力しない。
- 2026-09-12確認: `search_hex.txt` と既存検索テストの埋め込み応答は同一の5,815バイトだった。復元した検証資料の所在と原本の再検索結果は `docs/api-contracts.md` に残す。キャプチャ、セッション、トークンはGit管理しない。
- このWindows環境でSDKやKotlin daemonにAccessDeniedExceptionが出た場合、設定を変更する前に昇格したPowerShellで同じGradle検証を実行する。JDKは `JAVA_HOME` で明示する。
- 名前が空でも属性が存在するプレイリストは有効。rootlistのdecorated metadataを使い、items内の空URIを除く前に同じ位置のmetadataを対応付ける。
- メタデータ要求の国・catalogueは認証済みのアカウント属性から取得する。参照アプリのキャッシュ表示とオンライン取得は区別する。2026-09-12昼の502は改変・非ストア配布の参照APKを含む観測であり、通常版や提供元全体の障害を示すものではない。同日夜、ユーザーがGoogle Play通常版へ入れ直した。条件と集計は `docs/api-contracts.md`。
- `LibraryAccountTest` は `liveAccount=true` を明示してログイン済み実機で実行する。未ログイン前提の `SetupScreenTest` は分離したAVDへ限定する。AVDの全端末一括実行で実機の認証を消さない。
- 通信記録ツールは通常終了で端末のproxyと所有するadb reverseを復元する。親プロセスを強制終了するとfinallyが動かないため、時間制限付きで通常終了を待つ。異常終了時は `how-to-update.md` の復旧手順に従う。
- 画面の `assertIsDisplayed` は一部だけ見える項目でも通る。低い横画面でタイトルが切れたため、タイトルの元の高さと表示範囲の高さを比較し、画像も確認する。画面幅・高さは `LocalWindowInfo` を使用する。
- 依存の検証メタデータを新規生成した際は `tools/verify-platform-tools.init.gradle.kts` も実行し、WindowsだけでなくLinux/macOS用AAPT2を解決する。手順は `how-to-update.md`。
- 既存Gradleキャッシュから検証値を生成すると、解決済みdescriptorが親POMやBOMのmodule取得を省き、空キャッシュのCIだけで失敗する場合がある。2026-09-13に9ファイルの不足を確認した。空の検証用Gradle user homeと`--no-configuration-cache`で生成し、既存ハッシュが変更・削除されていないことを比較してから反映する。
- 2026-09-13の整理で、調査を終えた参照版の診断パッチ・受信機・認証比較の未追跡26ファイルと当時の設定差分を、ハッシュ検証済みの`session/archives/09-13-23-32.zip`へ保管し、現行ビルドから外した。認証・カタログの回帰検証には製品経路を通る`LibraryAccountTest`を使う。整理・復元方針は`how-to-update.md`を参照する。過去のRVPではルートのAndroid用DEXと拡張の生DEXが必要で、CLI 6の終了コード0だけでは全パッチ成功を保証しなかった。
- HTTP観測はJava/native境界の送信とコールバック呼び出し元へ入れる。ネイティブ宣言の改名・置換はJNI連携を壊すので避ける。要求ヘッダーはNUL区切りのキーと値。認証通信を除外し、DUMP権限付き制御と一時nonceで受信を制限する。
- 自己署名の照会は対象自身に限定する。公開証明書は入力APKの署名ブロックから抽出し、他パッケージの署名照会、OSの署名検証、リモートの認証結果と混同しない。署名変更したAPKはGoogle Play版へデータを保持したまま上書きできるとは扱わない。
- 2026-09-12夜に参照Android版の調査を中断してWindows版を優先した。2026-09-13の無音化修正では、ユーザーがGoogle Play通常版の読み取り専用解析の再開を許可した。通常版の改変・再インストールは許可されていない。Windows版の検索・アルバム詳細・楽曲一括装飾は実応答を取得済みだが、参照版の成功だけでPlayの機能完成としない。
- Windows版の認証サービス名にLogin5が含まれていても、HTTPも同じ方式とは限らない。2026-09-13、通常の更新はOAuth token endpointへの`grant_type=refresh_token`だったことをネイティブ関数で確認した。公開IDは実行中のplatform metadataと一致する。Playの保存済みLogin5資格情報のclient IDを書き換える方法は拒否された。
- Windows版の初期Sessionオブジェクトは更新後に古くなる。観測ではAuthorizationAPIのtoken providerまたはlive sessionを参照し、有効期限を確認する。以前の端末間認証値転送は一度の比較に限定されていたが、2026-09-13の継続作業では、ユーザーがPlay・参照アプリのログイン用トークンを今回のローカル検証環境間で必要最小限だけ一時利用することを追加許可した。ログ・Git・製品の認証方式へ組み込まず、音声鍵を抽出・保存しない。
- 2026-09-13確認: Windows版ではデバイス認証も観測したが、ユーザー要件に合わせ、製品は通常の認可コード＋PKCEへ変更した。`BrowserAuthorizationClient`が同一端末のループバックでコールバックを検証する。APの署名検証済みwelcomeから正規ユーザー名を取得する。
- カタログは`CatalogApiClient`へ接続。50曲一括の実応答とアルバムのページ分割を確認済み。ブラウザー認証には`DesktopClientProfile`の共通要求ヘッダーを使い、従来の端末SDKトークンと混在させない。
- 音声の旧APキー要求は実アカウントで拒否された。Windows版は専用HTTPキーAPIを使用する。Playの端末内再生は、配信情報の`manifestFileFormat=file_ids_mp4`で得る形式10とAndroid標準DRMを使う。CDN内MP4に初期化データがあることを確認済みで、独自に音声キーを保存・出力しない。
- 認証待機中の実機へAPKを再インストールしたりinstrumentationを実行すると、待機中の処理を停止させる。本人認証の完了・期限切れを確認するまでは、ビルド・JVMテスト・読み取り専用AVDで作業を進める。
- StateFlowの画面状態を更新するとき、`state.value.copy(items = suspendCall())`のように中断を伴う式を渡さない。中断前の状態を復活させ、同時に更新した確認コード画面や再生状態を消す。取得結果を先に変数へ受け、完了後の最新状態へ反映する。`SessionVerificationScreenTest`が再現する競合を維持する。
- Media3のDRM保持を無効にする値は`C.TIME_UNSET`。0は実サービス起動時に例外となるため、合成音源だけでなく本番のサービス初期化も検証する。停止位置はMediaController側の推定値と再生エンジンの確定値に差があるため、両者が停止することを個別に確認する。
- 検索のカタログunionはnullや取得不能項目を含む場合がある。既知の取得不能項目だけを除き、有効な項目まで一覧全体の失敗にしない。未知の形状・通信エラーは区別し、`CatalogJsonTest`と実アカウントの複数検索語で検証する。
- 一覧の保持・詳細の先読みはアカウント別に管理する。先読みへ通常の詳細表示が合流するため、一覧更新による内部無効化を画面操作によるキャンセルと混同しない。作成・編集・拡大プレイヤーの画面切り替えでは、保存領域を分岐の外側に置いてスクロール位置を維持する。
- 先読みの`suspend`呼び出しでも、呼び出し側のアカウント確認・Keystore読み取りは画面スレッドに残り得る。キャッシュ照会もIOで行い、先読み完了で一覧を再構築しない。低速ドラッグは可視行が変わらないため、可視行のdebounceだけでなく`isScrollInProgress`で開始を止める。実機の外部ジェスチャー性能測定は実時間の`ActivityScenario`を使う。`ComposeTestRule`は外部入力の待機中に描画時計が止まり、0フレームになる。再現・比較手順は`how-to-update.md`、結果は`verification.md`。
- 他タブの先読みを開始する入口と、先読み済み詳細を開く入口も検査する。2026-09-13、そこに残った同期`peekLibrary`/`peekDetail`が画面スレッドで認証ストアを読んでいた。表示済み一覧はViewModelの状態を使い、それ以外のキャッシュ確認はRepositoryのIO経路へ任せる。`LibraryBrowsingTest`が画面スレッド読み取り0件とキャッシュ再利用時の追加通信0件を検査する。
- rootlistの`ownerUsername`は表示名ではなくアカウントID。プロフィールAPIのProfile field1（ユーザーURI）を照合し、field2の表示名を使う。表示名と所有権判定用IDを分け、詳細や先読みでID表示へ戻さない。要求・根拠とキャッシュ範囲は`docs/api-contracts.md`。
- プレイリスト一覧はnoBackup配下の専用SQLiteへ保存する。空のHTTP200や改訂の無い応答を空一覧として保存しない。原本の全ページを検証してから差分をtransaction化し、同期開始時の世代を照合する。参照版のrootlist `revision` queryは目標改訂指定であり、sinceRevisionと推測しない。合成テストのnoBackupディレクトリは実データと分離する。
- 実音源の検証では、再生位置の進行・終端・ライセンス取得だけでフル再生成功としない。2026-09-13、約10秒以降にAACデコーダーが不正フレームを無音へ置換しながら位置だけ進む不具合を実機で確認した。復号後の音声レベルを測る回帰テストを使い、冒頭・中盤・終盤の音声と実機出力を確認する。ミュートした操作テストではこの不具合を検出できなかった。
- 2026-09-13の追加許可: 自分で作る非公開の検証用プレイリストだけを作成・名前/説明/画像/収録曲編集・削除してよい。既存のプレイリストは変更しない。必要なOAuth再認可、診断用APKコピーの改変・計測を分離環境で行うこと、サブエージェントの並列作業も許可された。実機のGoogle Play通常版と既存データは維持し、課金・公開は行わない。SDK用アプリの新規登録は必要性が未確認であり、登録を前提に進めない。
- 旧保存認証の無操作移行を調べたが、APの認証・保存資格情報での再接続後もKeymasterは403、音声配信情報はHTTP 200・media空だった。識別子を変更しただけで解決扱いにしない。失敗記録は`verification.md`。再生時に認証ページを自動起動しない。
- ログインを再試行する際、以前の処理のfinallyが新しいコールバック待機を閉じないよう、所有する待機オブジェクトだけを閉じる。キャンセル・ViewModel破棄時もリスナーを閉じ、state不一致・重複パラメーター・異なるパスを受け付けない。
- 実機ではブラウザーのログイン中にPlayがcached-process freezerの対象となり、コールバック受信が前面復帰まで遅れた。`LoginCallbackService`をユーザー開始のログイン中だけ前景実行し、完了・キャンセル・期限切れで停止する。音声の偽再生やバッテリー設定の変更では対処しない。遅延したブラウザー応答は`BrowserReturnTest`で確認する。
- 約10秒以降の無音は再生用の認証要求を修正して解消した。`PlaybackAuthorizationProvider`が保存済みのPlay自身のログインから必要な認証を内部取得し、標準Media3/DRMへ渡す。SDKアプリ新規登録・外部プレイヤー・参照資格情報の持ち込みは不要。元認証のアカウント/値、両認証の期限、付与先を検査する。根拠と経路は`docs/api-contracts.md`。
- 実アカウントは`LibraryAccountTest`、操作は`LivePlaybackTest`を明示実行する。後者はミュートするため、`fullTrack=true`の完走をフル音声再生の証明にしない。音声は`AudioOutputTest`を使い、同テストの`fullTrack=true`で背景・中盤・終盤と自然終端まで確認する。結果と端末条件は`verification.md`。
- IME対応はActivityの`enableEdgeToEdge`とManifestの`adjustResize`をそろえる。テストHostも本番と同じ設定にし、Compose rootの表示判定だけでなく実画面の位置/可視領域と画像で確認する。低い横画面では必要に応じて見出しを畳み、保存ボタン全体を表示する。
- 端末内ネットワークでIPv6の既定経路がない場合もある。2026-09-13の認証通信では接続候補への切替を無効にするとConnectExceptionとなり、通常の接続フォールバックで成功した。TLS検証や付与先制限を弱めず、時間上限を維持して接続候補を切り替える。
- 通常ログインの更新トークンは更新ごとに入れ替わることを実測した。認証の保存には書き込み完了を確認する`commit()`を使い、メモリー上の再読込だけで保持を検証しない。プロセス再起動後の連続更新と、期限切れ状態からの自動更新も確認する。
- 2026-09-15、フレッシュインストール直後と初回ログイン直後に`ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED`で10秒以降が復号できず、時間経過で再生できる場合があった。再生派生認証の初回取得が複数往復（sessiontransfer→OTT→公開bundle最大8MB→TOTP→token→client-token）のため、DRMスレッド上の`runBlocking`取得が最初の暗号境界に間に合わないことが原因。`LicenseHttpClient`は認証例外をNETWORKへ変換せず伝播させ、`AuthenticatedDrmCallback`は原因を保持して包装する（秘密値は保持しない）。`PlayViewModel`はログイン直後に`playbackAuthorization.prepare()`を裏で事前取得し、曲開始3経路（`play`・`playDetailTrack`・`seekLyrics`）では再生前に取得待ちしてから`localPlayback.play()`する。失敗時はDRMコールバックの再取得に任せ、再生自体は止めない。回帰は`LicenseHttpClientTest`、`AuthenticatedDrmCallbackTest`、`PlaybackAuthorizationProviderTest`。実機のフル音声確認は`AudioOutputTest(fullTrack=true)`で行い、未実行の実機検証は`verification.md`へ残す。
- 2026-09-16、連続再生は成功するがシークや曲切替で同エラーになる場合があった。原因は連続取得によるライセンスのHTTP 429であり、認証の期限・更新とは無関係。429/503は認証再取得ではなく同じ要求・認証で送り直し（`Retry-After`優先、上限60秒、既定10秒・30秒・60秒で最大3回）、`LicensePacer`で要求全体を4秒以上空ける。再試行の所有権は取得処理へ一本化し、プレイヤー側の即時再送は`DrmRetryPolicy`で止める。重なり中の取得不能確定時は鳴っている曲を止めず切り替えを断念して通知する。回帰は`LicenseHttpClientTest`の再試行・間隔・原因連鎖検査。
- 2026-09-23、okhttp 5はKMP構成で`okhttp-android`（AAR・asset経由のPublicSuffixList）と`okhttp-jvm`（JAR・クラスパス資源）に分かれた。JVM単体テストがAAR側を解決するとCookie処理で`Unable to load PublicSuffixDatabase.list`になるため、`UnitTestCompileClasspath/UnitTestRuntimeClasspath`だけ`okhttp-android`→`okhttp-jvm`へdependencySubstitutionする（square/okhttp#8927）。製品・instrumentationはAARを維持する。`Call`の手書き実装には`tag()`4種と`addEventListener`の追加も必要。検証メタデータは原則空Gradle homeと`--no-configuration-cache`で生成する。2026-09-25のWindows環境ではGradle 9.7.1の空homeが展開済みJARのAccessDeniedで失敗したため、既存homeで`--refresh-dependencies`を使い、旧ハッシュ不変と新規値の公式配布物一致を確認した。`tools/verify-platform-tools.init.gradle.kts`は版カタログの現行AGPに対応するAAPT2を選び、Linux/macOS/Windows分を解決する。詳細は`how-to-update.md`と`verification.md`。
- 2026-09-25、固有名除去のため内部名称を中立名へ統一した（`MusicContent`・`MusicRepository`・`ServiceApiClient`・`ServiceApiException`・`AuthException`・`ClientTokenClient`・`Login5Client`、`AppConstants.CLIENT_ID/USER_AGENT`）。変更禁止のAPI固定値は、`spotify:` URI scheme、`*.spotify.com`・`*.spotifycdn.com`等のホスト、`Spotify-App-Version`・`X-Spotify-Tr`等の要求ヘッダー、UA中の`Spotify/`トークン、`com.spotify.music`等のprotoパッケージ、`application/vnd.collection-v2.spotify.proto`、`spotify.metadata.*`・`spotify.automix.proto.*`等の型URL、`spotify:image:`等の派生URI、fixture JSONとcaptureツールの通信値、公開ID・版の値自体。保存キー・Keystore別名・画像キャッシュ名は移行付きで改名し、旧値は`LEGACY_*`定数と移行テストの旧データ投入だけに残す。
- 2026-09-25、#18の転送403は旧`/authorize`による認可に再生転送権限がないことが原因。`Downloads/flows_chrome`の成功した参照デスクトップのブラウザー認可は`/oauth2/v2/auth`で同じ公開client ID・28権限（`transfer-auth-session`を含む）・`creation_flow=desktop`・PKCE・DPoP鍵識別子を使う。これをPlayの通常ログインへ適用し、razrで新規コード交換、権限付与、Cookieなしの転送HTTP 200・再生セッション生成を確認。保存後、実音声を約310秒の自然終端まで確認した。Pixelの旧セッションは維持しログアウトしない。旧WebViewは黒画面のため導線を再認可へ変更し、v0.8.1で手動Cookie入力UIを撤去し、既存保存分の内部フォールバックだけ維持する。詳細は`verification.md`と`docs/api-contracts.md`。
- 2026-09-24、再生失敗の解析用に匿名化エラー報告を追加した。`PlaybackErrorReport`は分類・計測値だけを保持し、例外メッセージ・トークン・URL・曲名・生URIを保持しない（曲特定はSHA-256短縮ハッシュ＋linked一致真偽）。`LocalPlayback`の直前64件リング＋失敗時のスナップショットを`PlayViewModel`経由でプレビュー表示し、`ACTION_SEND`／issues/newプリフィルで利用者の共有操作に委任する。自動送信・GitHub認証保持はしない。JVMでは`PlaybackException`を構築できない（`SystemClock.elapsedRealtime`スタブ）ため原因連鎖を受ける別経路で検査する。回帰は`PlaybackErrorReportTest`。ユーザー向け文言は`diagnostics.xml`11言語へ追加する。
- 2026-09-25、専用署名の公開候補APKをrazrへ`adb install -r`するとv0.7.0 debug版からv0.8.0 release版へ認証データを保持して更新できた。releaseはdebuggableでなく、保存ライブラリ表示、実画面からの再生位置36→69秒とDRMエラーなし、停止操作を確認した。一方、既存のdebug向け`AudioOutputTest` APKでminify済みreleaseをinstrumentすると、テストランナーが`kotlin.jvm.internal.Intrinsics`と`androidx.tracing.Trace`を解決できず開始前にcrashする。これは製品の通常起動でなくテストAPKの依存構成に限る。release版の復号後レベルは未計測であり、同ソースのdebug版での自然終端検査と区別する。詳細は`verification.md`。
- 2026-09-26、ユーザー報告ではPlayだけログアウトしても標準ブラウザーのログイン状態が残り、次の認可で以前のWeb認証が再利用される。コード上も`SessionManager.clearSession()`はPlayの保存セッションだけを消し、ブラウザーCookieには触れない。認証を最初からやり直す場合は本人がブラウザー側の音楽サービスからログアウトする。Playのログアウト・再認可前とログイン画面にこの注意を示す。Pixelの既存セッションを検証目的でログアウトしない。画面検査は`SessionVerificationScreenTest`、詳細は`verification.md`。
- 2026-09-26、`SecureSessionStoreTest`の2件失敗はv0.8.0時点からの既存不具合で、今回の画面変更とは無関係。原因は(1)`SESSION_SCHEMA_VERSION` 3→4更新時の移行テスト期待値の更新漏れ（実装は4で再保存するため期待値を4へ修正）、(2)偽装`Editor`の`remove()`が委譲先の実Editorを返すため後続`commit()`の偽装が効かず保存失敗を再現できない（`remove`のオーバーライド追加で修正）。保存処理のメソッドチェーンに終端以外の呼び出しが加わったら偽装側の全メソッドを確認する。回帰は分離AVDの同テスト8件。
- 2026-09-27、#19の検証でKotlinの`interface by delegate`がJava interfaceのdefault methodを転送せず、`DrmSessionManager.prepare/release`が空実装になって実音源初回でクラッシュすることを確認した。DRM wrapperでは初期化・解放・preacquire等のdefault methodを明示委譲し、`getCryptoType`より前にprepareが届く回帰を維持する。`RetainedDrmSessionManagerTest`と`DrmRequestLifetimeTest`。
- 2026-09-27、#19では429/503の待機期限を全エンジンで共有する。60秒超のRetry-Afterは短縮再送せず失敗を返し期限を維持する（09-16の60秒上限より優先）。成功した標準DRMセッションだけ30秒・engine最大2件保持し、取得待ちは保持せずcloseSessionからHTTP待機を取り消す。ログアウト等の全消去は世代を更新し、遅れたreleaseで旧セッションを再保持しない。鍵・PCMは保存しない。`docs/playback-transitions.md`。
- 2026-09-27、10aの#19実音源診断で、同曲シークの追加ライセンス要求は現曲ではなく後続曲の`preacquireSession`だった。600秒の音源バッファが後続曲を読み込み、シークごとに後続曲の先行DRMセッションをclose/openしていた。任意のpreacquireはEMPTYにし、decoderの通常acquireと明示したcrossfade準備へ取得を限定する。暗号化音源キャッシュは維持する。`LivePlaybackBurstTest`で同曲連続シーク追加POST 0を検査する。


<a id="agents-6"></a>

移動元: [AGENTS.md](AGENTS.md)。

## Dependabotラベルの運用

- 明示指定のラベルはDependabotが自動初期化するとは限らない。既定ブランチの設定を正本とする専用workflowで不足分だけ維持する。検証と復旧は `how-to-update.md` のDependabotラベル節、回帰は `.github/tests/test-dependabot-labels.py`。既存の自動マージ・承認は別のworkflowであり、ラベルの変更で回避しない。
- 2026-10-15、#23はPR CI成功後に既存の自動処理でマージ・ブランチ削除されたが、そのmainコミットのCIは新規起動しなかった。自動マージ済みを手動マージし直さず、`mergedBy`・`mergeCommit`と検証済みheadとのツリー一致を確認する。PR CIとmain CIを区別し、mainの確認には対象SHAのrunを使う。


<a id="readme-3"></a>

移動元: [README.md](README.md)。

## ビルド

ビルドには次の環境が必要です。

- JDK 17以上
- Android SDK Platform 37 / Build Tools 37.0.0

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

生成APKは `app/build/outputs/apk/debug/app-debug.apk` です。


<a id="readme-5"></a>

移動元: [README.md](README.md)。

## 依存更新の自動処理

Dependabot は対象の依存関係を毎週確認します。patch／minor／major 更新は PR のチェック（CI）が成功した後に自動で squash merge されます。CI の失敗ジョブは 1 回だけ再実行します。再失敗した PR は残して手動で修正します。


<a id="update-1"></a>

移動元: [how-to-update.md](how-to-update.md)。

## 前提

- `COMMON-AGENTS.md`と`AGENTS.md`を全文確認し、`git status --short --branch`で既存差分を確認する。
- JDK 17以上と、ビルド設定が指定するAndroid SDKを用意する。バージョンの正本は`app/build.gradle.kts`と`gradle/libs.versions.toml`。
- 新規認証は通常のブラウザーログインと自動コールバック、保存一覧はネイティブAPI、作品の詳細と検索はカタログAPIを使う。[通信の根拠と記録](docs/api-contracts.md)を先に確認する。再生にペアリングを要求する方式へ戻さない。


<a id="update-2"></a>

移動元: [how-to-update.md](how-to-update.md)。

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

Gradle Wrapperの更新でも、Gradle内部で使うKotlinの依存が変わる場合がある。アプリのKotlin版が変わらなくても、通常ビルドと`verifyPlatformTools`によるメタデータ生成・検証を行う。Gradle 9.8.0への更新では、ビルドクラスパスの`kotlin-reflect`・`kotlin-stdlib` 2.4.10の検証値が必要だった。アプリのKotlin Gradle Plugin 2.4.20とは区別し、検証無効化や一律の信頼設定で回避しない。

Composeプラグインの更新でも、バージョンカタログの変更だけでは検証値は追加されない。プラグインマーカーのPOMから関連するKotlin依存まで生成対象に含め、通常の依存検証でCIと同じタスクが成功することを確認する。`gradle/osv-scanner.toml`のメタデータ限定例外に記載した実際の解決版も、`buildEnvironment`と照合して更新する。

2026-09-25のこのWindows環境では、Gradle 9.7.1の空homeで`GeneratedClassCompilationException`が発生し、内側の原因は展開済みGradle JARへの`AccessDeniedException`だった。昇格と別ディレクトリでも同じため、既存homeから`--refresh-dependencies --no-configuration-cache --write-verification-metadata sha256`で全ビルドを行い、旧ハッシュの変更・削除がないことと新規ハッシュを公式Google Mavenの実ファイルで確認した。`tools/verify-platform-tools.init.gradle.kts`は版カタログの現行AGPに対応するAAPT2だけを選び、過去版がメタデータに残っていてもLinux/macOS/Windowsの3種を検証する。

7. OSV-Scannerで依存関係を監査する。依存情報の外部送信を伴う照会が許可されない環境では、公開DBをダウンロードしてローカルで照合する。DBの更新日時も記録する。既存の例外と緩和策は`gradle/osv-scanner.toml`および`SECURITY.md`を確認する。

```powershell
osv-scanner scan source --lockfile gradle/verification-metadata.xml --config gradle/osv-scanner.toml --offline-vulnerabilities --download-offline-databases
```
8. [検証手順](verification.md)に従い、画面操作、ログイン、ライブラリ、再起動後の認証保持を確認する。実アカウントの検証は対象端末を明示して実行する。

実音源の再生状態と操作の確認は、ログイン済みの許可された端末で `LivePlaybackTest` に `livePlayback=true` を指定する。先頭から終端まで位置を確認する場合は `fullTrack=true` も指定し、終了まで再インストールや別のinstrumentationを実行しない。このテストは音量を一時的に0にするため、音声が正常に続くことの証明にはならない。

音声の無音化は `AudioOutputTest` に `liveAudio=true` を指定して確認する。実機から音を出し、既知の検証曲の16〜30秒区間に音声が含まれるかを測定する。`fullTrack=true`も指定すると途中でホーム画面へ移し、曲の終端・中盤・終盤の音声も確認する。音声・鍵・認証情報を記録せず、端末全体の音量を変更しない。再生位置だけの成功判定へ戻さない。

新規ブラウザー認可の権限を調べる場合は、Pixelの既存セッションを保持し、許可された別端末で`DesktopAuthorizationAccountTest`に`liveDesktopAuth=true`を指定する。テストは本人のブラウザー操作後に権限と再生セッション生成を確認し、保存済みログインを変更しない。製品画面では設定の「ログインし直す」を使い、成功時だけ保存認証を置き換える。実音声テストは製品と同じ再生認証の事前取得を経てから開始する。

Playのログアウトは端末内の保存セッションを消すが、標準ブラウザー側のログイン状態は消さない。認証を最初からやり直す検証では、本人がブラウザーで音楽サービスからログアウトしてからPlayで再認可する。Playから他アプリのCookieを消す設定変更や、Pixelの旧セッションのログアウトを検証の代わりにしない。

0.8.1以降はハンバーガーメニューの「Webセッション」入力画面を表示しない。旧版で保存したWebセッションは暗号化セッション形式4から読み込み、転送が拒否された場合の内部フォールバックだけに使用する。画面削除を理由に旧保存認証や既存のライブラリデータを消さない。起動画面はAndroidX SplashScreenを使い、Android 12以降ではベクターアイコンのアニメーション、Android 11以前では共通の終了アニメーションを確認する。

特定の旧版だけが再生に失敗する場合は、検索で見つかる別版ではなく、実際に保存されている曲を確認する。`StreamingAccountTest`へ`liveStreaming=true`と`albumQuery`（保存アルバム名の一意な部分文字列）、または`playlistName`（完全一致）と`trackTitle`（曲名の部分文字列）を渡すと、元の一覧を変更せず再生情報を解決する。両組を指定すると2検査を実行する。検査ログにある元の曲URIを`AudioOutputTest`と`LivePlaybackTest`の`trackUri`へ渡して、音声・シーク・前後移動・リピートも確認する。配信情報だけの成功を再生成功と扱わない。

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


<a id="update-3"></a>

移動元: [how-to-update.md](how-to-update.md)。

## 許可済み端末の通信確認

参照アプリがユーザーCAを信頼し、所有者が通信確認を許可した検証端末でだけ実行する。mitmproxyが必要。元のHTTP proxyを記録してから一時設定し、指定時間の終了時に復元する。

```powershell
$captureDevice = Read-Host '通信確認を許可した端末ID'
pwsh -File tools/capture-library-traffic.ps1 -Device $captureDevice -Seconds 60
```

記録先は`captures/`配下に表示される。ライブラリと選択した再生関連の通信を対象とし、認証・Cookieヘッダーを保存前に除去する。ライセンス通信は本文を保存せず、要求・応答のサイズとステータスだけを残す。ほかの本文やURLには私的なライブラリ情報が含まれるため原本を公開しない。`tools/inspect-library-capture.py`で構造・ステータスだけを抽出し、個人情報を確認してから文書へ取り込む。保存内容の検査は`python tools/test-capture-tools.py`で行う。

親プロセスの強制終了では復元処理が動かない場合がある。通常は指定時間の終了を待つ。異常終了した場合は、その記録先の`proxy-state.json`を読み、対象端末・元のproxy・使用ポートを確認する。現在のproxyが記録した検証用設定と一致することを確かめてから元の値へ戻す。元の値が`null`または空なら設定を削除する。`adb reverse --remove`はその記録にあるポートだけを指定し、他の転送を消さない。保存PIDを使ってプロセスを止める場合も、起動時刻とコマンドラインがこの記録ツールのものか確認する。最後に`adb shell settings get global http_proxy`と`adb reverse --list`で復元を確認する。


<a id="update-4"></a>

移動元: [how-to-update.md](how-to-update.md)。

## 調査用の作業ファイルの整理

製品へ取り込む修正は対応するテストとともにコミットする。調査終了後の試作を通常ビルドの設定やDebug Manifestへ残さず、現行の製品経路を検査するテストを維持する。通信原本、認証情報、署名鍵、APKや依存キャッシュをソースと一緒にコミットしない。

2026-09-13には、参照版の診断パッチ・通信受信機・認証比較の未追跡26ファイルを整理した。削除前に追跡済み4ファイルの変更前後も含めてローカルの`session/archives/`へ保管し、全30原本のバイト一致とSHA-256を確認した。これは完了した調査の保存であり、実装途中の製品機能の退避ではない。保管先とハッシュは同日のsessionメモに記録している。

過去の調査を再開する場合は、アーカイブを別の空ディレクトリへ展開し、`manifest.json`の元コミット・各ファイルのハッシュと`changes.patch`を確認する。既存ファイルへ一括上書きせず、必要な箇所だけを現在の認証・署名・テスト構成と照合して取り込む。Gitのignoreやstashだけで未完成の実装を隠して整理済みとは扱わない。


<a id="update-5"></a>

移動元: [how-to-update.md](how-to-update.md)。

## CI・配布

- Linuxでは`gradlew`の実行属性が必要。`git ls-files --stage gradlew`のmodeが`100755`であることを確認する。
- `app/build.gradle.kts`のversionName/versionCodeと、CHANGELOGの対応するバージョンをそろえる。mainのCIが成功したコミットに`v<versionName>`タグを作成してプッシュする。
- `.github/workflows/release.yml`はタグとAPKのバージョンを照合し、JVM・lint・最適化したreleaseビルドを行う。その後、署名済みAPKとSHA-256ファイルを生成し、抽出した変更履歴とともにGitHub releaseへ公開する。成功したrunにも`play-v<versionName>`成果物を保持する。
- 署名には、所有者が登録先を明示承認した同リポジトリの`PLAY_KEYSTORE_BASE64`、`PLAY_STORE_PASSWORD`、`PLAY_KEY_ALIAS`、`PLAY_KEY_PASSWORD` Secretsを使う。署名ステップだけに渡し、一時鍵ファイルはfinallyで削除する。登録時は秘密値を標準入力へ改行を付けず渡し、引数・ログへ出さない。
- CIとローカル検証は共通の`tools/sign-release.ps1`を使用する。対象ID、バージョン、debuggableでないこと、署名証明書が期待する公開版の識別子であることを確認し、署名検証とSHA-256ファイル生成まで行う。署名鍵を更新する場合はスクリプトの期待値と`SECURITY.md`を同じ変更で更新し、既存インストールからの移行も検証する。
- CIや実機の未実施項目を成功と記録しない。サービス側のエラーはHTTPの結果とメタデータ内の結果を分けて記録する。

ローカル署名にはBuild Tools 37.0.0とJDK 17を使う。`PLAY_STORE_PASSWORD`と`PLAY_KEY_PASSWORD`は実行プロセスの環境変数に設定し、コマンド引数やログへ展開しない。鍵は`.signing/play-release.p12`、aliasは`play-release`。このWindows環境のパスワードは`.signing/release-password.dpapi`に暗号化して保持している。復号するときはファイル末尾の改行を`Trim()`で除き、同じWindowsユーザーの`ConvertTo-SecureString`を使う。`.signing/`はGit管理外とし、鍵と復旧可能なパスワードを安全に保管する。

```powershell
$metadata = Get-Content -Raw app/build/outputs/apk/release/output-metadata.json | ConvertFrom-Json
$version = $metadata.elements[0].versionName
.\tools\sign-release.ps1 -Apk app/build/outputs/apk/release/app-release-unsigned.apk -Version $version `
  -KeyStore .signing/play-release.p12 -KeyAlias play-release `
  -Output "build/outputs/play-$version.apk" -AndroidSdk $env:ANDROID_HOME
```

パスとバージョンは対象リリースに合わせる。署名証明書SHA-256を`SECURITY.md`と照合し、`apksigner verify --verbose --print-certs`の成功も確認する。配布は署名済みAPKと隣の`.sha256`だけとする。公開処理だけを手動で復旧する場合も、検証済みrunのhead SHAとタグを確認し、`gh release create`の`--verify-tag`と`--notes-file`で同じ変更履歴を使用する。公開後はGitHubからAPKを取得してハッシュと署名をもう一度照合し、実機へ入っている最終APKも一致確認する。GitHubへプッシュする前に依存関係の脆弱性監査を再実行する。

既存開発版の実機は、初回だけローカルの署名履歴による移行が必要。`tools/sign-release.ps1`へ`-Lineage .signing/legacy-to-release.lineage`を付けるとAndroid 9以降用の移行APKを作る。このAPKはv3署名専用であり、一般配布のAPKとして扱わない。分離AVDの`ReleaseUpgradeFixtureTest`（`prepareReleaseUpgrade=true`、ranchu限定）で暗号化ログイン・保存一覧・5スロットを準備し、移行APK→通常リリースAPKの順に`install -r`して画面から保持を確認する。実機で署名不一致になっても認証やデータを消して対処しない。共用の開発秘密鍵を外部へ送らない。

2026-09-25のrazrでは、同一専用証明書で署名した0.7.0 debug版から0.8.0 release版へ`install -r`でき、保存ログインを維持した。minify済みreleaseへ既存のdebug向け`AudioOutputTest` APKをinstrumentするとテストランナー側のKotlin/AndroidXクラス不足で開始前に落ちるため、これを製品再生の失敗と混同しない。release版は通常の画面から再生を操作してライセンスエラーと位置の進行を確認し、復号後音声レベルは同ソースdebug版の`AudioOutputTest`で別に測る。

音質設定の変更では、`AudioEffectsScreenTest`を日本語縦画面、英語800×360、アラビア語960×1800で実行する。30番目までの調整、5スロットの保存・呼び出し・上書き・名前変更・削除、IMEと保存失敗を検査する。`AudioEffectsRouteTest`は実際のメニュー接続と、画面を戻ったときの一覧位置保持を検査する。保存・DSP・実音源の検証方法は[音質設定の設計](docs/audio-effects.md)を参照する。


<a id="update-6"></a>

移動元: [how-to-update.md](how-to-update.md)。

## 復旧

### 保存操作・検索・タイマーの追加検証

- `SavedLibraryActionsTest`と`CatalogNavigationTest`は、コレクション書込、所属判定・削除の原本確認、7カテゴリ検索、ジャンルのプレビューと続きのページを検査する。
- 分離AVDで`ContentActionsScreenTest`、`SleepTimerDialogTest`、`LibraryBrowsingTest`、`PlaylistStartupCacheTest`、`PlaylistViewModelTest`を実行する。お気に入りは画面用の項目として一つだけ追加し、ディスクの原本プレイリスト一覧へ混ぜないことを検査する。画面画像は`screenshotPrefix`を指定して保存できる。
- 実アカウントの読み取りは`CatalogNavigationAccountTest`へ`liveCatalog=true`を渡す。「jazz」「lo-fi」「トリッカル」「インターネット」の7カテゴリ検索はRepository経由で所有者プロフィールの取得まで検査する。曲詳細、アーティスト、ラジオ、番組、ジャンルの各導線も検査する。
- 検索画面の回帰は`SearchRegressionScreenTest`へ`liveCatalog=true`を渡し、実機だけを指定して実行する。「トリッカル」の全件/プレイリスト、「インターネット」の全件/ジャンルと、検索に含まれる「お気に入りの曲」の詳細を開く。保存・フォロー・再生の書き込み操作は含まない。
- お気に入りの一時変更には別途明示許可を得てから、`SavedItemsAccountTest`へ`liveSavedItems=true`を渡す。未登録の曲・アルバム各1件だけを追加・削除し、原本全ページで既存内容を維持したことを確認する。途中失敗のジャーナルが残ると次の試験を停止するため、対象を確認し一時追加分だけを削除してから再開する。
- `PlaylistAccountTest`の`livePlaylists=true`は、新しく作る非公開リスト内で曲・アルバムの所属チェックと追加・削除も検査する。既存のリストを検証対象として変更しない。
- タイマーのOS権限、分離AVDでの実アラーム・停止・消灯の検証は[タイマー手順](docs/sleep-timer.md)を参照する。実機へ端末管理者権限を自動付与しない。
- 認証キャンセル回帰は`SecureSessionStoreTest#cancellingSearchDuringRefreshStillPersistsRotatedCredentials`を分離環境で実行する。実際に失効した更新トークンは復元できないため一度通常ログインを行い、その後`LibraryAccountTest#consecutiveRefreshesUseTheSavedCredentials`で保存情報の連続更新を確認する。ログイン待機中の実機を再インストール・instrumentationで停止しない。

不具合のある更新は対象コミットをrevertし、既存の認証情報を保ったまま以前のAPKへ戻す。暗号化セッションの形式を変更するときはスキーマ番号と移行・破損時の処理を同時に検証する。外部APIが変わった場合、失敗を空一覧へ置き換えて隠さず、利用者へ取得失敗を示す。

現在のセッション形式は4。旧形式2・3を読み込んだ際に暗号化して移行する。形式4を未対応の古いAPKへ戻すと保存認証を読めないため、再ログインが必要になる。旧APKへの切り戻しで認証状態を保持できるとは扱わない。本人がブラウザー認証中の端末には再インストール・強制終了・instrumentationを実行せず、完了または期限切れを確認する。

### 再生遷移・同期歌詞・アーティストの追加検証

通常のビルド/JVM検査に加え、分離AVDで`PlaybackSettingsScreenTest`、`LyricsPanelTest`、`ArtistPageScreenTest`、`PlaybackTransitionsTest`、`PlaybackTransitionFailureTest`、`PeakNormalizerAudioProcessorTest`、`PlaybackTransitionStoreTest`を実行する。日本語縦画面・英語の低い横画面・狭幅RTLを含め、`screenshotPrefix`で保存した合成画面を確認する。タイマーは従来の専用AVD試験で最大12秒の減衰も確認する。

実アカウントの読み取りは`LyricsAccountTest(liveLyrics=true, trackUri指定)`、`ArtistPageAccountTest(liveArtist=true)`、`LiveAutomixAccountTest(liveAutomix=true)`。アーティストの検証対象は名前の一致だけで決めず、公式ページで確認したIDを使う。紹介文が未提供の場合も実応答とモデルの一致を検査し、存在しない本文を補わない。

実音声は`LivePlaybackTransitionsTest`に`liveTransitions=true`を指定する。Automixは`automixPlaylistUri`と、必要ならその原本に含まれる`automixFromUri`・`automixToUri`を指定する。2つの音声の実出力を区別して測り、アプリ全体の音量は変えず、検証後にEQと再生設定を戻す。[再生の設計](docs/playback-transitions.md)に詳細をまとめる。

フォローの書き込みは未フォロー1組の一時変更と復元を明示許可された場合のみ、`ArtistFollowAccountTest`へ`liveArtistFollow=true`を指定する。以前のジャーナルが残ると新規操作を止める。アカウント・対象を確認し、その一時追加分だけを解除して元の原本集合を確認する。

実測用のログは時刻・型・件数・音声レベルだけにし、歌詞、音声、認証情報、音声鍵を保存しない。メディア情報の診断控えはGit外に置く。


<a id="update-7"></a>

移動元: [how-to-update.md](how-to-update.md)。

## Dependabot PR の更新

前提は `.github/dependabot.yml` と PR 用 CI（CI）です。更新 PR の head SHA と `gh pr checks <PR番号>` の結果を確認してください。patch／minor は全チェック成功後に自動取り込みされます。初回 CI 失敗は failed jobs のみを 1 回再実行し、再失敗した PR は残して手動で修正します。

設定を変えたときは `actionlint .github/workflows/dependabot-automation.yml` と実際の PR の Actions 結果を確認します。問題があれば呼び出し先の共通 workflow SHA を直前の検証済み値へ戻すコミットを push します。取り込まれた依存更新に問題があれば通常の revert コミットで復旧します。

CI 完了より Dependabot の分類が遅れる場合は、`callback_workflow_file` が指す呼び出し側 workflow を `workflow_dispatch` し、同じ PR 番号・head SHA・全チェックを再確認する。呼び出し側のファイル名を変える際はこの入力も一緒に更新する。


<a id="update-8"></a>

移動元: [how-to-update.md](how-to-update.md)。

## 連続再生操作の回帰検証

ライセンスや曲切替を変更した場合は、JVMの`LicenseHttpClientTest`、`AuthenticatedDrmCallbackTest`、`DrmRequestLifetimeTest`、`RetainedDrmSessionManagerTest`と、端末上の`PlaybackTransitionFailureTest`を実行する。取得待ちの連続選曲、異なる一覧への選曲中の次曲操作、待機中の一時停止・停止、切替失敗時の元の音声維持を含める。

許可されたログイン済み端末だけで`LivePlaybackBurstTest`へ`liveBurst=true`を渡す。同曲の連続シークに追加ライセンス要求がないこと、短時間のA→B→Aで再利用すること、連続選曲後に古い要求が再送されないこと、復号後の非無音を確認する。テストは数値だけを計測し、再生設定を端末内の専用ジャーナルへ退避して終了時に復元する。途中でプロセスが落ちた場合は同じテストを再実行して退避設定を復元し、退避記録を勝手に消さない。実音声の自然終端は別途`AudioOutputTest(liveAudio=true, fullTrack=true)`で確認する。

短い連打だけで通常の連続切替を検証済みとしない。`LivePlaybackSequenceTest(liveSequence=true)`で異なる6曲を先頭から約15秒ずつ聴き、「次へ」で順番に切り替える。ユーザーの再生設定をそのまま使用し、各曲の10〜15秒に復号後の非無音があること、初回音声までの時間、曲ごとのPOSTが1回であること、429がないことを確認する。曲名・URI・音声本文はログへ残さない。

耐久性は同クラスの`tenDifferentTracksWithRandomMiddleAndLateSeeksRemainAudible`だけへ`liveEndurance=true`を渡す。異なる10曲を各15秒以上再生したうえで、中盤2回・終盤2回へ交互にランダムシークし、40回すべての移動後に別々の非無音窓を10件以上確認する。乱数の種は20260927で再現可能にし、位置は曲長に応じて計算する。終盤で明示的な次曲準備が始まることは許容するが、POST合計は10曲に対して10回であり、同曲シークのたびに要求が増えないことを検査する。


<a id="update-9"></a>

移動元: [how-to-update.md](how-to-update.md)。

## Dependabotラベルの維持

`.github/workflows/dependabot-labels.yml` は既定ブランチのDependabot設定を読み、`updates` と `multi-ecosystem-groups` が明示指定するラベルの不足分だけ作成します。設定変更、ラベルの編集・削除、毎日04:17 UTC、手動実行で検査します。既存ラベルの色・説明やPRへの付与状態は変更しません。書込権限は専用ジョブの `issues: write` に限定し、PRの検証ジョブは読み取り専用です。

ラベルが不足する場合、Actionsの「Maintain Dependabot labels」を既定ブランチで手動実行し、`All configured Dependabot labels exist` を確認します。権限エラーやarchivedラベルとの競合は失敗として通知されます。Dependabot設定のラベル名を別名へ付け替えたり、CI・承認条件を無効化して回避しないでください。

変更時は次を実行し、Actions上でも検証してください。

```powershell
python -m pip install PyYAML==6.0.3
python .github/tests/test-dependabot-labels.py
actionlint .github/workflows/dependabot-labels.yml
```

Dependabot更新そのものの成功は、更新ジョブのログと結果で確認します。指定ラベルが無い場合はGitHubの仕様では無視されるため、実際のジョブ失敗をラベル不足だけと決めつけないでください。更新チェックの手動実行はGitHubのDependabot更新画面で提供される場合に使用し、Actions APIが再試行不可と返す実行は成功扱いしません。


## 完了条件の指定時点

2026-09-13、ユーザーがプレイリスト編集も今回の完了範囲へ追加し、不在中の自律作業を指定した。
