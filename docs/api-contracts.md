# 認証・ライブラリ通信の確認記録

## 現在の製品経路（2026-09-13）

Windows参照版1.2.93.667の要求と実応答を基準に、認証・カタログ・音声配信の処理を接続した。Android参照版の調査はユーザー指定で中断している。以下の旧Android記録は調査履歴であり、現在の製品経路とは区別する。

| 対象 | 確認した契約・処理 | 検証範囲 |
| --- | --- | --- |
| 新規認証 | `/oauth2/device/authorize`から認証コードとブラウザーURLを取得し、指定間隔で`/api/token`へデバイス認証grantを送る | Windows参照版の要求を観測し、Play実機でも認証開始がHTTP 200になることを確認。本人の認証完了は待機中 |
| 認証更新 | refresh tokenで`/api/token`を呼ぶ。更新値が省略された場合は従来値を維持する | Windowsの通常更新を観測。合成応答で待機、間隔延長、期限切れ、更新を検証 |
| 正規ユーザー名 | OAuth応答にはユーザー名がないため、署名検証したAPとの鍵交換後、OAuth資格情報で認証したwelcomeを読む | Windows上で参照版の正規ユーザー名との一致を確認。資格情報をログ・fixtureへ保存しない |
| 保存一覧 | コレクション・rootlistの既存protobuf契約を継続し、ブラウザー認証では専用の共通要求ヘッダーを使う | Windowsの同一OAuth認証で両APIのHTTP 200を確認。端末SDKトークンは混在させない |
| カタログ | Windows版のGraphQL永続化クエリでアルバム詳細・曲詳細・50曲単位のメタデータ・3種類の検索を取得する | 実応答を取得。公開作品だけを匿名化したfixtureで画像・タイトル・アーティスト・長さ・利用可否を検証 |
| 作品詳細 | アルバムはページ送り、プレイリストはネイティブの収録URIをページ取得して曲メタデータを対応させる | 画面へ接続し、重複曲の位置、再試行、アルバムへの移動を検証 |
| 音声配信情報 | `/track-playback/v1/media/{track-uri}?manifestFileFormat=file_ids_mp4`から形式10を選択し、`/storage-resolve/v2/files/audio/interactive/10/{file-id}`で配信先を解決する | Windowsの認証で配信情報とCDNのHTTP 206を確認。取得したMP4にDRM初期化情報があることを確認 |
| 端末内再生 | Media3、Android標準DRM、`/widevine-license/v1/audio/license`を使う。保存する音声は暗号化された配信データのみ | 証明書要求のHTTP 200は確認済み。Play自身のコンテンツライセンス取得と実楽曲の再生は未確認 |

認証の公開ID、要求ヘッダー、カタログのクエリhashは対応するデータ層のソースコードを正本とし、文書へ重複転記しない。Windows上の認証サービス名と実HTTP方式は一致するとは限らない。初期Sessionオブジェクトの値も更新後に古くなるため、調査では有効期限を確認したtoken providerを使った。端末間の認証値転送は承認済みの一度の比較で終了・削除し、製品へ流用していない。

音声の旧APキー要求は実アカウントで拒否された。Windowsの専用キーAPIをPlayで再実装せず、端末の標準DRMへ接続している。配信先の署名付きURLはメモリー内で短時間保持し、ログへ出力しない。ディスクのキャッシュキーにはファイルIDを使い、署名付きURLが変わっても同一音源の暗号化データを再利用する。

セッション形式3は形式2を暗号化したまま移行する。認証更新とログアウト・アカウント切り替えが競合した場合、古い更新応答が現在のセッションを上書きしない。新規接続が完了するまでは従来の保存認証を維持する。

この契約は参照版の実測に基づき、公開APIとしての互換性を保証するものではない。Play自身の新しい認証での確認状況は[検証記録](../verification.md)を参照する。

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
