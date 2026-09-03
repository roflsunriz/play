# APK 解析記録

## 対象

- ファイル: `target.apk`
- パッケージ: [REDACTED]
- バージョン: `9.1.78.2218`（versionCode `145505446`）
- SHA-256: `D02220EE8261D8D76C49BA2E179AEAECF3D8C162370E4CD079B9E7F8ED4251CB`
- SDK: minSdk 24、targetSdk 37、compileSdk 37
- ABI: arm64-v8a、armeabi-v7a、x86、x86_64

解析には Android Build Tools 37 の AAPT2 と JADX 1.5.6 を使用した。JADX は 13 DEX・62,600クラスを処理し、[REDACTED]名前空間では6,772ソースファイルを索引化した。難読化された巨大メソッドなど818件は完全に逆コンパイルできなかったため、以下はクラス構成、公開モデル、マニフェスト、リソース、読めた処理を突き合わせた観測結果である。

## アプリ構成

- コンポーネントは Activity 86、Activity Alias 4、Service 39、Receiver 36、Provider 12。
- 実体のエントリーポイントは `[REDACTED]MainActivity` で、通常アイコンは有効な `MainActivity` Aliasから接続される。別色アイコンもAliasとして用意されている。
- `androidx.compose.ui.tooling.PreviewActivity` と独自のEncore UI部品が共存しており、Composeと従来Viewの混成構成である。
- 各ABIに12本、合計48本のネイティブライブラリがある。音源処理の中心はJava/Kotlin層だけで完結していない。

## 認証とログイン状態

- `authentication`、`appauthorization`、`login` が分離され、OAuthアクセストークンはトークン本体、種別、有効期限を持つモデルとして扱われる。
- OAuthセットアップ、トークン交換、Login5、SSO、外部ログイン、Quick Loginは別経路として分離される。
- Android Keystoreを使うAES-GCM鍵生成処理があり、秘密値を平文設定だけへ置く設計ではない。


## プレイヤー状態と操作

- `player` 名前空間だけで166ファイルあり、状態、コンテキスト、キュー、制限、音質、エラー、コマンドが分離されている。
- `PlayerState` は再生中、一時停止中、バッファ中、現在位置、基準時刻、再生速度、現在トラック、前後トラック、コンテキスト、制限、キュー改訂番号を保持する。
- 現在位置は保存位置だけでなく、基準時刻からの経過時間と再生速度を使って算出する。この考え方を本アプリのシーク表示更新へ採用する。
- 再生、準備、再開、一時停止、次、前、シーク、シャッフル、コンテキストリピート、1曲リピートは別コマンドモデルである。本アプリもRepositoryの個別操作として分離する。

## キャッシュとオフライン

- [REDACTED]本体には `offline`、`offline_esperanto`、`download`、`storage`、`appstorage` の独立領域がある。
- オフライン項目にはライセンス状態、端末キー、デバイス上限、コンテキスト進捗、ダウンロード要求、セグメント取得、完全キャッシュ判定が含まれる。
- 汎用ディスクキャッシュは、アクセス順 `LinkedHashMap`、サイズ上限、ジャーナル、破損時再構築、上限超過時の非同期削除を持つLRU方式である。本アプリのアートワークキャッシュにも、Coilの128 MiB上限付きDisk LRU Cacheを採用した。
- キャッシュと設定は別パスとして管理される。本アプリも画像キャッシュを `cacheDir/[REDACTED]_artwork_cache`、認証設定を暗号化SharedPreferencesへ分離する。

## ハプティクス

- ボタン状態の確定では `performHapticFeedback` を使用し、Android 11以降と以前でフィードバック定数を分ける箇所がある。
- 再生ボタンの特定遷移ではAndroid 10以降の定義済みVibrationEffect、旧版では時間指定振動へフォールバックする。
- 本アプリでは全操作へプラットフォームのハプティクスを接続し、OSが端末能力とユーザー設定に合わせて出力を選べる方式を優先する。

## 本アプリへ反映した判断

1. minSdk 24 / targetSdk 37とし、[REDACTED] APKと同じAndroid世代を対象にする。
2. 認証、API通信、セッション保存、UI状態、再生操作を別ファイル・別責務にする。
3. リピートはOFF、コンテキスト、1曲の3状態、シャッフルは独立状態として扱う。
4. トラック位置は再生中にローカルで時刻更新し、操作後と手動更新時にサーバー状態へ再同期する。
5. 操作可能要素に安定したテストタグを付け、表示言語に依存しないUIテストを可能にする。

## 深い静的解析（Login5 / spclient / 楽曲キャッシュ）

### 認証方式の刷新（Login5通常ログイン）

- 旧実装のPKCE OAuth（Client ID入力 + accounts.spotify.com/authorize）を廃止し、**通常のSpotifyアカウント（ユーザー名+パスワード）でログインできる方式へ刷新**した。
- 対象APKの認証本体はネイティブ(Rust)実装で、Java層にはEsperanto RPCのprotobuf契約が露出していた。`spotify.authentication.login5.impl.proto.Login5` の `authenticate` に `AuthenticateRequest{credentials=password}` を送る契約を抽出した（`EsAuthenticateRequest$AuthenticateRequest.java` 等）。
- ワイヤ契約は公開OSS実装（librespot / librespot-java / spotcontrol）から確定した:
  - `POST https://login5.spotify.com/v3/login`（protobuf、`Client-Token`ヘッダ必須）
  - Android用クライアントID `9a8d2f0ce77a4e248bb71fefcb557637`（client_secretは存在しない）
  - hashcash（SHA-1）チャレンジ解決が必須。`login_context`のSHA-1後半8バイトをシードに、prefix+suffixのダイジェスト末尾10ビットが0になるsuffix(16バイト)を探索する
  - 成功時は `LoginOk{username, access_token, stored_credential, access_token_expires_in}` を返し、`stored_credential`（バイナリ）で以後の再認証・トークン更新を行う
- Client Tokenは `POST https://clienttoken.spotify.com/v1/clienttoken`（Android用 `ConnectivitySdkData`）で取得する契約。
- API呼び出しは `spclient.wg.spotify.com` をベースにし、`Authorization: Bearer <login5トークン>` と `Client-Token` ヘッダを送る（`OAuthHelper.smali` の `DEFAULT_WEBGATE_HOST` と `p/g82.java` の認証ホスト一覧から確定）。

### 楽曲ストリーム・キャッシュの意味契約

- 楽曲ストリームURLは固定CDNを持たず、Esperanto RPC `GetMediaManifest` が返すマニフェストからCDN base URL一覧とurlPathテンプレートを配布する方式（`EsDownload$MediaManifestResponse`、`p/rro0.java`）。urlPathは `{{profile_id}}` / `{{segment_timestamp}}` を置換して構成する。
- セグメント取得は `RequestSegmentData(streamerId, urlPath, cdnBaseUrl[], start, end, metadata)` のバイトレンジ指定で、応答に `previouslyCached / fromNetwork / hadCacheError` を持つ（`EsDownload$RequestDataResponse`）。キャッシュ優先・ネットフォールバックの契約。
- 完全キャッシュ判定は `IsFileFullyCached(urlPath, contentForm)`、部分キャッシュは `GetFirstCachedSegmentIndex` で先頭位置を取得する。
- ディスクキャッシュの意味契約（ExoPlayer SimpleCache相当）:
  - 容量上限は `segment_cache_max_size_bytes`（既定最大100MB）
  - LRU押し出しはファイル名のタイムスタンプでアクセス順を永続化し、読み取り時にリネームでタッチする
  - インデックスはバイナリファイル + SQLiteの二重化、起動時に実ファイル走査で再構築し実長不一致スパンを削除する
  - ダウンロード状態は QUEUED→DOWNLOADING→COMPLETED/FAILED で管理し、起動時に割り込み分を再キューする
- 本アプリでは、公開契約で取得できる楽曲プレビュー（`preview_url`）を対象に、**容量上限付きLRUディスクキャッシュ（TrackCache）**を実装した。SHA-256キー、最終アクセス時刻による押し出し、起動時の破損ファイル除去（再構築）を持つ。ストリームURL取得契約（Esperanto RPC）はネイティブ実装のためJava単体では確定不能であり、プレビュー音源をキャッシュ対象として意味契約を適用した。

### spclientのWeb APIプロキシ（確定・検証必要）

- spotcontrol（Go）実装では、Login5トークンはspclientエンドポイント専用で、`api.spotify.com` のWeb APIはOAuth2(PKCE)トークンを要求すると明記されている。
- 本アプリはユーザー判断により **spclient.wg.spotify.com のみ** を使用し、ライブラリ表示・検索・再生操作も同ホストへ送る。spclientがWeb APIパスをプロキシする契約に依存するため、実機での動作確認が必要（verification.mdに記録）。

### 動的解析

Android 36の隔離AVDへ対象APKをインストールし、未ログイン状態のコールド起動を確認した。

- Launcher Aliasの `MainActivity` から実体の `[REDACTED]MainActivity` へ遷移した。
- 起動完了までの計測値は約2.2秒だった。
- 未ログイン画面は `login_activity_root` 配下に、スクロール可能なコンテナ、[REDACTED]ロゴ、価値説明、無料登録、ログインボタンを持つ。
- AVDのBluetoothシステムサービスがクラッシュする環境問題が発生したため停止して切り分けた。[REDACTED]プロセス自身のクラッシュではない。

同じAVDでPlayも起動し、ライト配色、720×1280の狭幅、アラビア語RTL、縦スクロールをスクリーンショットで確認した。この検証により、Composeルート背景の欠落とシステムバー安全領域の不足を検出して修正した。
