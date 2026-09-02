# Spotify Android APK 解析記録

## 対象

- ファイル: `com.spotify.music_9.1.78.2218-145505446_minAPI24(arm64-v8a,armeabi-v7a,x86,x86_64)(nodpi)_apkmirror.com.apk`
- パッケージ: `com.spotify.music`
- バージョン: `9.1.78.2218`（versionCode `145505446`）
- SHA-256: `D02220EE8261D8D76C49BA2E179AEAECF3D8C162370E4CD079B9E7F8ED4251CB`
- SDK: minSdk 24、targetSdk 37、compileSdk 37
- ABI: arm64-v8a、armeabi-v7a、x86、x86_64

解析には Android Build Tools 37 の AAPT2 と JADX 1.5.6 を使用した。JADX は 13 DEX・62,600クラスを処理し、Spotify名前空間では6,772ソースファイルを索引化した。難読化された巨大メソッドなど818件は完全に逆コンパイルできなかったため、以下はクラス構成、公開モデル、マニフェスト、リソース、読めた処理を突き合わせた観測結果である。Spotifyのソースコードは本リポジトリへ複製していない。

## アプリ構成

- コンポーネントは Activity 86、Activity Alias 4、Service 39、Receiver 36、Provider 12。
- 実体のエントリーポイントは `SpotifyMainActivity` で、通常アイコンは有効な `MainActivity` Aliasから接続される。別色アイコンもAliasとして用意されている。
- `androidx.compose.ui.tooling.PreviewActivity` とSpotify独自のEncore UI部品が共存しており、Composeと従来Viewの混成構成である。
- 各ABIに12本、合計48本のネイティブライブラリがある。音源処理の中心はJava/Kotlin層だけで完結していない。

## 認証とログイン状態

- `authentication`、`appauthorization`、`login` が分離され、OAuthアクセストークンはトークン本体、種別、有効期限を持つモデルとして扱われる。
- OAuthセットアップ、トークン交換、Login5、SSO、外部ログイン、Quick Loginは別経路として分離される。
- Android Keystoreを使うAES-GCM鍵生成処理があり、秘密値を平文設定だけへ置く設計ではない。
- Spotify本体の内部SSO・ネイティブ認証経路は第三者アプリ向け公開APIではないため再利用しない。本アプリでは公式Web APIのAuthorization Code with PKCE、ループバックリダイレクト、Android Keystore暗号化保存を採用する。

## プレイヤー状態と操作

- `player` 名前空間だけで166ファイルあり、状態、コンテキスト、キュー、制限、音質、エラー、コマンドが分離されている。
- `PlayerState` は再生中、一時停止中、バッファ中、現在位置、基準時刻、再生速度、現在トラック、前後トラック、コンテキスト、制限、キュー改訂番号を保持する。
- 現在位置は保存位置だけでなく、基準時刻からの経過時間と再生速度を使って算出する。この考え方を本アプリのシーク表示更新へ採用する。
- 再生、準備、再開、一時停止、次、前、シーク、シャッフル、コンテキストリピート、1曲リピートは別コマンドモデルである。本アプリもRepositoryの個別操作として分離する。

## キャッシュとオフライン

- Spotify本体には `offline`、`offline_esperanto`、`download`、`storage`、`appstorage` の独立領域がある。
- オフライン項目にはライセンス状態、端末キー、デバイス上限、コンテキスト進捗、ダウンロード要求、セグメント取得、完全キャッシュ判定が含まれる。
- この機構はSpotify内部サービス、ライセンス、ネイティブ層と一体であり、Web APIは音声バイトを提供しない。Spotifyポリシーもコンテンツのダウンロード／stream rippingを禁止しているため、本アプリで音源キャッシュは実装しない。
- 汎用ディスクキャッシュは、アクセス順 `LinkedHashMap`、サイズ上限、ジャーナル、破損時再構築、上限超過時の非同期削除を持つLRU方式である。本アプリのアートワークキャッシュにも、Coilの128 MiB上限付きDisk LRU Cacheを採用した。
- キャッシュと設定は別パスとして管理される。本アプリも画像キャッシュを `cacheDir/spotify_artwork_cache`、認証設定を暗号化SharedPreferencesへ分離する。

## ハプティクス

- ボタン状態の確定では `performHapticFeedback` を使用し、Android 11以降と以前でフィードバック定数を分ける箇所がある。
- 再生ボタンの特定遷移ではAndroid 10以降の定義済みVibrationEffect、旧版では時間指定振動へフォールバックする。
- 本アプリでは全操作へプラットフォームのハプティクスを接続し、OSが端末能力とユーザー設定に合わせて出力を選べる方式を優先する。

## 本アプリへ反映した判断

1. minSdk 24 / targetSdk 37とし、Spotify APKと同じAndroid世代を対象にする。
2. 認証、API通信、セッション保存、UI状態、再生操作を別ファイル・別責務にする。
3. プレイヤー操作はSpotify Web APIへ接続し、Spotify内部の非公開クラスや音声配信機構を複製しない。
4. リピートはOFF、コンテキスト、1曲の3状態、シャッフルは独立状態として扱う。
5. トラック位置は再生中にローカルで時刻更新し、操作後と手動更新時にサーバー状態へ再同期する。
6. 操作可能要素に安定したテストタグを付け、表示言語に依存しないUIテストを可能にする。
7. Spotify提供のメタデータにはSpotifyへのリンクと帰属表示を付ける。

## 動的解析

Android 36の隔離AVDへ対象APKをインストールし、未ログイン状態のコールド起動を確認した。

- Launcher Aliasの `MainActivity` から実体の `SpotifyMainActivity` へ遷移した。
- 起動完了までの計測値は約2.2秒だった。
- 未ログイン画面は `login_activity_root` 配下に、スクロール可能なコンテナ、Spotifyロゴ、価値説明、無料登録、ログインボタンを持つ。
- AVDのBluetoothシステムサービスがクラッシュする環境問題が発生したため停止して切り分けた。Spotifyプロセス自身のクラッシュではない。
- ユーザー資格情報を扱わない方針により、ログイン後画面、実音源再生、キャッシュ実ファイル、操作時ハプティクスの動的計測は行っていない。

同じAVDでPlayも起動し、ライト配色、720×1280の狭幅、アラビア語RTL、縦スクロールをスクリーンショットで確認した。この検証により、Composeルート背景の欠落とシステムバー安全領域の不足を検出して修正した。
