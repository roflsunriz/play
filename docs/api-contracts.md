# 認証・ライブラリ通信の確認記録

## 現在の製品経路（2026-09-13）

Windows参照版の要求と実応答を基準に認証・カタログ・音声配信を接続し、通常版APKと公開Web配信コードとの照合、Play自身の実機検証で再生用認証とプレイリスト編集を補った。以下の旧Android記録は調査履歴であり、現在の製品経路とは区別する。

| 対象 | 確認した契約・処理 | 検証範囲 |
| --- | --- | --- |
| 新規認証 | `/authorize`へPKCE・state付きで通常ログインを開始。同じ端末のループバックへ返った結果を検証し、`/api/token`で交換する | 実アカウントの通常ログインと保存が完了。実機のブラウザーでも、遅延した合成応答から追加タップなしで復帰できることを確認 |
| 認証更新 | refresh tokenで`/api/token`を呼ぶ。更新値が省略された場合は従来値を維持する | Windowsの通常更新を観測。合成応答で更新・期限切れ・キャンセル・PKCE対応を検証 |
| 正規ユーザー名 | OAuth応答にはユーザー名がないため、署名検証したAPとの鍵交換後、OAuth資格情報で認証したwelcomeを読む | Windows上で参照版の正規ユーザー名との一致を確認。資格情報をログ・fixtureへ保存しない |
| 保存一覧 | コレクション・rootlistの既存protobuf契約を継続し、ブラウザー認証では専用の共通要求ヘッダーを使う | Windowsの同一OAuth認証で両APIのHTTP 200を確認。端末SDKトークンは混在させない |
| カタログ | Windows版のGraphQL永続化クエリでアルバム詳細・曲詳細・50曲単位のメタデータ・3種類の検索を取得する | 実応答を取得。公開作品だけを匿名化したfixtureで画像・タイトル・アーティスト・長さ・利用可否を検証 |
| 作品詳細 | アルバムはページ送り、プレイリストはネイティブの収録URIをページ取得して曲メタデータを対応させる | 画面へ接続し、重複曲の位置、再試行、アルバムへの移動を検証 |
| 音声配信情報 | `/track-playback/v1/media/{track-uri}?manifestFileFormat=file_ids_mp4`から形式10を選択し、`/storage-resolve/v2/files/audio/interactive/10/{file-id}`で配信先を解決する | Windowsの認証で配信情報とCDNのHTTP 206を確認。取得したMP4にDRM初期化情報があることを確認 |
| 端末内再生 | 保存済みログインから派生させた再生用認証と対応する端末認証を、Media3・Android標準DRM・`/widevine-license/v1/audio/license`へ渡す。保存する音声は暗号化された配信データのみ | 2曲の10秒以降の音声、約5分の背景再生の終端・中盤・終盤、再生操作を実機で確認 |

認証の公開ID、要求ヘッダー、カタログのクエリhashは対応するデータ層のソースコードを正本とし、文書へ重複転記しない。Windows上の認証サービス名と実HTTP方式は一致するとは限らない。初期Sessionオブジェクトの値も更新後に古くなるため、調査では有効期限を確認したtoken providerを使った。端末間の認証値転送は許可された比較のみに使用して終了後に削除し、製品へ流用していない。

音声の旧APキー要求は実アカウントで拒否された。Windowsの専用キーAPIをPlayで再実装せず、端末の標準DRMへ接続している。配信先の署名付きURLはメモリー内で短時間保持し、ログへ出力しない。ディスクのキャッシュキーにはファイルIDを使い、署名付きURLが変わっても同一音源の暗号化データを再利用する。

2026-09-13の無音化調査では、[公開再生SDK](https://sdk.scdn.co/embedded/index.js)の配信形式・DRM初期化手順も照合した。形式10は128 kbps、11は256 kbps、12と13は複数DRM向け、14と15はCBCS向け。旧seektableとMP4内の初期化情報は一致したが、新しいsidecarとは一部が異なった。最終的には既存MP4の初期化情報を変更せず、認証要求の修正で音声が正常になった。ライセンスHTTP 200や再生位置だけを成功判定にしない。実測の条件は[検証記録](../verification.md)を参照する。

### 一覧メタデータ・検索・先読み

rootlistの`meta_items`は元のitemsと同じ位置で関連付ける。公開配信コードのencode/decodeと照合し、`MetaItem.attributes`はfield2、`length`はint32のfield3、`ownerUsername`はstringのfield5、`statusCode`はsint32のfield9と確認した。説明は既存のListAttributes field2を使う。空URIはページ内の位置を保った後でRepositoryが除く。確認資料はGit管理外の`build/qa/playlist-source/`にある公開bundleの控え。

作成者のアカウントIDと表示名は別に保持する。ネイティブrootlistの`ownerUsername`を表示名に流用せず、`GET /user-profile-view/v3/profile/{username}`へ`playlist_limit=0`、`artist_limit=0`、`episode_limit=0`、`Accept: application/x-protobuf`を指定する。公開bundleのmodule 86576にある要求とProfileのencode/decodeを照合し、field1がユーザーURI、field2が表示名と確認した。返却URIと対象ユーザーを照合し、表示名だけを使う。プロフィールはアカウント別に最大256件・同時4取得で共有し、手動更新では再取得する。閲覧できないプロフィールや空の表示名をIDで置き換えず、その他の通信エラーは失敗として扱う。

未提供の説明・曲数・日付はnullとし、既知の空説明や0件と区別する。リスト長はサービスの総要素数であり、曲以外を含む場合がある。アルバムと楽曲は既存のカタログ応答からアーティスト、親アルバム、日付、長さなどを読み取る。

検索のnullスロット、null union、`NotFound`、`RestrictedContent`は既知の取得不能項目として除く。正しいURIと名前を持つ再生不可曲は残す。未知のwrapper、`GenericError`、通信やGraphQLの失敗は検索結果の空配列へ置き換えない。修正後、実機の保存済み認証で英語・日本語・1文字の4検索語が成功した。

一覧3種類と作品詳細はアカウント別のメモリに保持し、アルバムと楽曲が使う同じcollection pagingも共有する。さらにプレイリスト一覧は`noBackupFilesDir/playlist-library/`の専用SQLiteへ保存する。ファイル名はアカウントのSHA256で分け、認証情報を含めない。起動時は保存済みの完全な一覧を先に公開し、原本の全ページを取得してからURI単位の追加・削除・変更と順序をtransactionで反映する。未変更のmetadata行は書き直さない。

rootlistの`revision` queryは、参照コードの`fetchContents(newRevision)`から目標改訂を指定している。前回以降の差分要求と推測して使わない。通信では軽量な原本一覧を照合し、個別詳細・プロフィール照会とDB更新を必要な項目に絞る。応答に改訂が無い、ページが進まない、途中で改訂が変わる、件数とtruncatedが矛盾する場合は完全な一覧とせず、保存済み内容を維持する。

SQLiteはschema1、最大10,000項目・1行64KiB・metadata計16MiB。破損や未対応schemaはその専用キャッシュだけ再生成する。保存の世代番号を比較し、同期中の作成・変更・削除・ログアウトや並行同期が完了した後に古い結果を上書きしない。作成者の名前と実照会時刻を保存し、起動時と自動同期では24時間以内の値を再利用する。手動更新ではプロフィールも再照会する。

詳細の取得全体は同時2件、メモリ保持は24件か計6000項目まで。先読みはスクロール停止から350ms後に、表示中の先頭2項目と直後2項目のうちプレイリスト・アルバムだけを対象にする。専用の待ち行列は最大4件・同時1件とし、スクロール再開時は待機分を取り消す。進行中の1件は完了して共有キャッシュへ保存し、画面から開く要求もその結果を共有する。アカウント切り替え時には進行中の処理も取り消す。

先読みでは一覧の再構築や表示状態の更新を行わない。キャッシュ照会時のアカウント確認、Keystore読み取り、応答解析も含めて画面スレッドの外で実行する。`suspend`宣言や内側のHTTP処理だけでは、呼び出し側の同期処理は別スレッドへ移らない。低速ドラッグで可視行が変わらない間も`isScrollInProgress`で先読みの開始を止める。`DetailPrefetcherTest`、`ViewportPrefetchTest`、`LibraryBrowsingTest`と実機の`LiveScrollPerformanceTest`で検証する。

ViewModelから一覧・詳細を開く入口にも同期のRepositoryキャッシュ照会を置かない。表示済み一覧はViewModelの状態から即時に切り替え、未公開の一覧と詳細のキャッシュはIO上の`library`/`detail`内で確認する。同じ結果をネットワークから取り直さず、既存のアカウント確認は維持する。詳細取得を待つ間は選択した作品を表示し、他の操作へ移った場合はその要求を取り消す。

手動更新と書き込み後に該当キャッシュを無効化する。内部無効化で待機中の画面要求が取消された場合は、同じアカウントで画面がまだ有効なら再取得する。ログアウトや利用者の画面操作による取消とは区別する。検証は`AccountMemoryCacheTest`、`SpotifyRepositoryCacheTest`、`PlaylistDiskCacheTest`、`PlaylistStartupCacheTest`にある。

### 再生用認証の自己取得

`PlaybackAuthorizationProvider`はPlayが保存した通常ログインを元に処理し、参照アプリから取得した値を製品へ組み込まない。処理はHTTPで完結し、ブラウザーや外部プレイヤーを開かない。

1. 元のBearerで`POST /sessiontransfer/v1/token`へ遷移先URLを送り、短時間有効なトークンを得る。この経路は通常Windowsの`TokenExchangerImpl`から確認し、Play自身の実機要求でも200・有効期間約299秒を確認した。
2. 通常のOTTログインページのCSRF設定と局所Cookie状態を保ち、`/api/login/ott/verify`、必要な場合は`/api/login/ott/approve`を実行する。認証ページへのBearer転送はしない。遷移先を許可されたHTTPSホストへ限定する。
3. 同じページの`appServerConfig`と、そのページが指定した公開main bundleを取得する。公開設定の構造・変換・TOTPの条件を確認して、`/api/token`へ`reason/productType/totp/totpServer/totpVer`を送る。これは公開クライアント用の計算で、本人のMFA設定を取り出す処理ではない。
4. 非匿名で有効期限内の認証と返却Client IDを確認し、ページの版・Playの端末情報で対応するclient-tokenを取得する。元のカタログ用認証や別のClient IDの端末認証と混在させない。
5. 両認証の短い期限から余裕を引いてメモリー内で再利用する。取得前後のアカウントと元認証を照合し、変更時は結果を破棄する。Cookieと派生認証はディスクへ保存しない。

要求の初期化にはサーバー時刻を使い、更新は`/api/server-time`から時刻を得る。強制更新時だけ`X-Spotify-Tr:true`を付ける。公開設定が変わって検証できない場合は失敗として扱い、設定値や版を推測して埋めない。実装・入力検証は`PlaybackAuthorizationClient`、`PublicWebTokenConfiguration`、`WebClientTokenClient`に分離している。

ライセンスPOSTは自動リダイレクトを拒否し、資格情報の許可ホストを確認する。ライセンスの401と認証転送の401は区別し、それぞれ1回だけ再取得する。プロビジョニングと復号は標準DRMへ任せ、音声鍵や復号音声を保存しない。

比較では再生用Bearerだけ、端末認証だけの変更、元のデスクトップ用プロファイルでの自己取得だけでは無音が残った。上記の組をPlayが自力で取得してからは、標準DRMの復号後PCMと本番の再生サービスの両方で音声を確認した。根拠となる公開コードの控えは`build/qa/playlist-source/`、自己取得診断は`build/qa/own-web-audio-check.log`、本番経路の検証は`verification.md`に集約する。

セッション形式3は形式2を暗号化したまま移行する。認証更新とログアウト・アカウント切り替えが競合した場合、古い更新応答が現在のセッションを上書きしない。新規接続が完了するまでは従来の保存認証を維持する。

この契約は参照版の実測に基づき、公開APIとしての互換性を保証するものではない。Play自身の新しい認証での確認状況は[検証記録](../verification.md)を参照する。

### ペアリング不要という要件への修正

当初採用したデバイス認証は、ユーザーに追加のコード入力を求めるため取り下げた。通常の認可コードとPKCEを使い、結果は同一端末へ自動で戻す。ループバックは動的ポートで起動し、state、パス、重複パラメーター、要求サイズを検証する。標準の接続方式は[RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.html#section-7.3)、参照クライアントの認可コード経路は[公開実装](https://github.com/librespot-org/librespot/blob/dev/oauth/src/lib.rs)を確認した。

旧保存認証だけで必要な権限を取得する方法も実機で試した。APの署名検証と認証、発行された保存資格情報による再接続は成功したが、その後のKeymaster要求は、確認済みのデスクトップ・Android両識別子で403だった。旧アクセストークンによる音声配信情報はHTTP 200でもmediaが空であり、音声を取得できなかった。旧認証を無操作で移行できるとは扱わず、拒否された方法は製品へ接続していない。

## 記録の所在（2026-09-12）

- 前回の引き継ぎは `session/09-03-18-02.md`。2026-09-03に確認コード入力後のログイン成功とライブラリ画面への遷移を確認した記録がある。一覧の表示成功とは区別する。
- 元の通信記録の保存先は `%LOCALAPPDATA%/Temp/opencode/mitm/flows.flow` と記されていた。2026-09-12にその場所とCドライブ上の関連ファイル名を、通常実行・昇格実行で検索したが、読み取り可能な本体は見つからなかった。削除されたとは断定しない。
- リポジトリ直下の旧 `search_hex.txt` と検索テストの埋め込みhexは、同一の検索応答5,815バイトだった。元のデコード済み応答のSHA-256は `ee8d07f7995a0b2b44f0a57865f332e125486dc675e39e16469a6101726b2d1f`。
- この検索応答の原本を `captures/recovered/search-response-original.hex` へ保存した。`captures/` はGit管理外であり、新しい環境へcloneしても含まれない。
- Git管理対象の検証データは `app/src/test/resources/captures/search-response.hex`。応答のentityフィールドのみを残し、ページ送り用トークンと周辺制御情報を除いた5,629バイト。HTTPヘッダー、認証応答、パスワード、確認コード、メールアドレスは含めない。10曲・4アルバム・1プレイリスト・1検索セクションの形状を保持した。
- ライブラリのテストデータは実キャプチャではなく、下記の一次資料に基づく架空データ。通信全体を復元できたという意味ではない。

## 旧Android経路で確認した契約と修正

| 対象 | 正しい扱い | 旧実装の問題 |
| --- | --- | --- |
| パスワード認証 | `/v4/login`、認証情報field 111、確認コードとlogin contextを送る | 前回の実機成功経路を維持 |
| 保存認証による更新 | `/v3/login`、保存認証情報field 100 | 実装では更新もv4へ送っていた |
| プレイリスト一覧・詳細 | `SelectedListContent` field 1は改訂番号のbytes、field 15は時刻 | 改訂番号を整数として読み、以降の位置がずれた |
| 一覧ページ | `ListItems` field 1は位置、field 2は打ち切りフラグ、field 3は要素 | フラグを終端位置と解釈し、最初の50件だけを利用した |
| 保存アイテム | `CollectionItem` field 2は追加時刻の整数、field 3は削除フラグ | プレイリストの入れ子属性パーサーを流用した |
| 保存一覧のページ送り | 応答field 2は次ページ、field 3は同期用トークン。要求field 3へ次ページを渡す | field 2を変更トークンとして扱い、後続ページを取得しなかった |
| 拡張メタデータ要求 | `EntityRequest.query` は `ExtensionQuery`。アルバム9、曲10 | queryへランダムバイト列を埋め込んだ |
| メタデータの利用条件 | 認証済みアカウント属性から国・catalogueを取得し、要求ヘッダーへ設定する | 日本・無料契約を固定した |
| 曲のアートワーク・長さ | アルバムのcover groupを参照。durationはsint32をZigZag復号 | field 17の時刻を画像と誤読し、長さも2倍にした |
| 検索の詳細 | 曲・アルバム・プレイリストをそれぞれの型で読む | アルバムの発売年・プレイリストの件数を入れ子データとして読んだ |
| 未知フィールド | 長さを読む位置更新と、内容を飛ばす位置更新を分ける | 複合代入で長さ部分の消費を取り落とした |
| エラー | 通信・提供元・デコードの失敗を一覧の空と区別する | メタデータ取得の例外を空一覧へ変換した |

当時の経路では、プレイリストは`/playlist/v2/user/{username}/rootlist`と`/playlist/v2/playlist/{id}`、保存一覧は`/collection/v2/paging`、詳細情報は`/extended-metadata/v0/extended-metadata`、検索は`/searchview/v3/search`を使った。詳細情報と検索は現在カタログAPIへ置き換えている。動的な端末ヘッダーは必要性を実測してから追加する。

## 新しい実機記録

**同日夜の補足:** ユーザーから、改変やGoogle Play以外の配布元が表示・再生を妨げているとの説明があり、Google Playから通常版へ入れ直された。端末で`9.1.80.2221`、installerがストア、debuggableなしを確認した。以下の昼のキャプチャは改変版`9.1.82.1596`の記録であり、通常版の通信成功・失敗の基準には使わない。以降は新しい通常版を基準とし、ローカルな配布元判定と通信の観測方法を切り分ける。

2026-09-12、ユーザーが証明書を登録した後、インストール済みの参照アプリ`9.1.82.1596`を観測した。実際のAPKはdebuggableで、network security configのdebug-overridesはユーザーCAを信頼し、overridePinsを有効にしていた。これらのAPK設定を本作業で変更したわけではない。

- 原本はGit管理外の`captures/09-12-11-23-48/`、`09-12-11-40-13/`、`09-12-13-03-04/`、`09-12-13-10-16/`。取得ツールは認証・Cookieヘッダーを保存前に除き、元の通信を変更せずコピーを記録する。本文には私的なライブラリIDなどが含まれるので公開しない。
- 最新記録の構造・ステータスのみを[ライブラリ観測集計](library-wire-observation.json)へ取り込んだ。アクセストークンやクライアントトークンの値・ハッシュは含めず、同一性の比較にはその記録内だけで有効な連番を使う。
- rootlistの全69要素からフォルダー2件を除いた67件をPlayで取得できた。属性が存在して名前だけが空の1件は有効なプレイリストであり、取得失敗にしない。decorated metadataを利用し、全項目の詳細を個別に取り直さない。
- 保存一覧は8ページ、合計1,423件（アルバム39件、曲1,384件）を取得した。タイトルの取得とは区別する。
- アカウント属性の取得は`POST /user-customization-service/v1/customize`。CallerInfoと空のAccountAttributesRequestを送り、`UcsResponseWrapper.success.attributes`のmapから`country_code`と`catalogue`を読む。国・catalogue・16バイトのtask IDをメタデータ要求へ設定する。固定の国・契約種別に置き換えない。
- 参照アプリで観測した地域ホストと`Client-Feature-Id`（アルバムは`your_library`、曲は`track_metadata_loader`）、端末の`Time-Zone`を利用した。同一記録の保存一覧・プレイリスト・詳細取得で認証情報の切り替えは見られなかった。
- それでもPlayのアルバム・曲は応答内の502で失敗した。単件要求では403も観測した。参照アプリの新しい通信もHTTP 200ながら、曲種別10の1,397件とアルバム種別9の78件が応答内502だった。参照アプリの画面表示は確認できるが、新しい詳細通信の成功は確認できていない。キャッシュの影響を切り分ける必要がある。
- インストール済み参照アプリのHTTPキャッシュから、検索候補応答30件と検索応答1件を復元した。検索応答1件は旧hexの5,815バイトと同一。新しいPlayの「Nirvana」検索はHTTP 200、約231バイトでentityフィールド自体が0件。「Beatles」ではプレイリストが返る。検索条件の差を試しても曲・アルバムの取得は成立していない。

ネイティブライブラリにv3のメタデータ経路もあるが、HTTPの要求形式と成功応答を確認できていないため採用していない。旧要求やローカルRPCのメッセージを流用して成功とみなさない。現在の未達項目と再開条件は[検証記録](../verification.md)へまとめる。

## プレイリストの書き込み（2026-09-13）

通常Webの配信コードと通常版APKの型定義を照合し、Play自身のログインで検証用の非公開リストを作って確認した。新しいSDKアプリの登録や既存リストの変更は行っていない。実装は`PlaylistApiClient`と`PlaylistMutationProto`に分離している。

| 操作 | 確認した契約 |
| --- | --- |
| 新規作成 | `POST /playlist/v2/playlist`へ`OpList`を送り、opsはfield 1。返却`CreateListReply.uri`を検証する |
| 非公開設定 | `/playlist-permission/v1/playlist/{id}/permission/base`で`permissionLevel=BLOCKED`を確認し、その後rootlistへ`public=false`で追加する |
| 属性変更 | `POST /playlist/v2/playlist/{id}/changes`へ`ListChanges`。そのfield 2内の`Delta`もopsはfield 2で、作成の`OpList`とは異なる |
| 名前・説明・画像 | `UPDATE_LIST_ATTRIBUTES`の部分属性を使う。空の説明や画像削除はno-valueの対象フィールドとして明示する |
| 画像登録 | 画像アップロード先の`POST /v4/playlist`へJPEGを送り、返却`uploadToken`を`POST /playlist/v2/playlist/{id}/register-image`のfield 1へ渡す。返却picture bytesを属性へ保存する |
| 画像表示 | サイズ別画像URLが無い場合も、属性field 3のpicture IDから通常クライアントと同じ画像URLを解決する |
| 曲の追加・削除 | リスト変更のADD・REM操作を使い、URI・所有者・編集能力を確認する |
| プレイリスト削除 | 所有リストだけを対象にrootlistのREMを送り、原本一覧から除かれたことを確認する |

詳細の所有者と能力は、確認済みの`decorate=revision,length,attributes,timestamp,owner,capabilities`を要求して取得する。権限の失敗をUI上だけで隠さず、書き込み前にも検査する。

最初の作成要求は`Delta`と誤認したため、HTTP成功でも名前が空になった。公式コードのメソッド名だけではなく、実際に渡されるシリアライザーを追跡して`OpList`へ修正した。画像も登録成功だけでは完成にせず、実取得・デコード・画素照合まで確認した。通信層のJVM13件と実機の一巡検査が成功し、既存rootlistのURI集合が変わらないことを確認した。記録は`build/qa/playlist-account-fourth.log`。

失敗後に同じ画面から再保存する場合は、新規URIを保持して続きから処理する。書き込み検証用のURIジャーナルは同期保存し、削除済みであることを原本rootlistで確認した場合だけ古い記録を除く。名前・説明・JPEGサイズの入力上限はPlay側の制限であり、提供元全体の上限とは断定しない。

一次資料のローカル控えはGit管理外の`build/qa/playlist-source/web-player.585c4669.js`、`vendor~web-player.f748be6a.js`、`service-apks/reference-store/`。認証値・私的なリストの内容を検証fixtureへコピーせず、確認した契約から架空データを組み立てている。

## 一次資料

2026-09-12に公開実装の定義を確認した。以下は公開APIとしての安定性を保証する資料ではない。

- [プレイリストのprotobuf定義](https://github.com/librespot-org/librespot/blob/dev/protocol/proto/playlist4_external.proto)
- [保存一覧のprotobuf定義](https://github.com/librespot-org/librespot/blob/dev/protocol/proto/collection2v2.proto)
- [拡張メタデータ要求](https://github.com/librespot-org/librespot/blob/dev/protocol/proto/extended_metadata.proto)と[種別定義](https://github.com/librespot-org/librespot/blob/dev/protocol/proto/extension_kind.proto)
- [曲・アルバムのメタデータ定義](https://github.com/librespot-org/librespot/blob/dev/protocol/proto/metadata.proto)
- [認証更新の実装](https://github.com/devgianlu/go-librespot/blob/master/login5/login5.go)と[メタデータ要求の実装](https://github.com/devgianlu/go-librespot/blob/master/spclient/spclient.go)

## 検証データの再生成

```powershell
python tools/import-search-capture.py captures/recovered/search-response-original.hex app/src/test/resources/captures/search-response.hex
```

新しい通信記録が得られた場合は、同じスクリプトの入力へ`.flow`を指定できる。この形式のみPython環境にmitmproxyが必要。`--response-index`で検索応答の番号を選ぶ。原本は`captures/`に保管し、抽出後も内容を確認してからGitへ追加する。ライブラリ・認証の生データをそのままテストへ貼り付けない。
