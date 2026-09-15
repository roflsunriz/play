# アーティストページ

## 利用経路

検索のアーティスト名、楽曲のアーティストメニュー、関連アーティストから同じ詳細画面を開く。人気曲は既存の端末内再生・お気に入り・プレイリスト編集へ、ディスコグラフィーと参加作品はアルバム詳細へ、関連プレイリストはプレイリスト詳細へ接続する。

ソングラジオの候補はアーティストの人気曲を使う。選択した曲の既存ラジオ解決APIから提供元のプレイリストを取得する。関連プレイリストを無条件にラジオと呼んだり、曲名検索で代用したりしない。

## データ契約（2026-09-15）

公開クライアントのアーティスト画面とmain bundleの控えを `build/qa/playlist-source/` で照合した。要求名・hashの正本は `CatalogApiClient`、応答の変換は `ArtistCatalogJson`。この内部契約の将来の互換性は保証されない。

- 概要は `queryArtistOverview`。`artistUnion` のURIと型を照合し、`saved`、`stats.monthlyListeners/followers/worldRank`、`profile.biography.text/type` を保持する。未提供の統計値はnullであり、0に置き換えない。
- ディスコグラフィーは `queryArtistDiscographyAll` へURI・offset・limit・`order=DATE_DESC`を渡す。`discography.all.totalCount`と原本items件数により全ページを読む。項目はリリースグループで、公開画面のmodule 88814と同じく `releases.items[0]` を代表版とする。別作品の先頭要素を代用する処理とは異なる。
- アルバムの `type` を保持し、ALBUM、SINGLE、EP、COMPILATION、UNKNOWNを区別する。すべての作品を取った結果からアルバムとシングル・EPを切り替えられる。
- `relatedContent.appearsOn.items` は同じリリースグループ、`featuringV2` と `discoveredOnV2` は `items[].data` 内のプレイリスト、`relatedArtists` は直接アーティスト項目を持つ。実機では `featuringV2` に項目単位の `GenericError` が含まれた。公開アーティスト画面もこの2つの棚は `Playlist` の項目だけを表示している。これらの棚に限り、`GenericError`・`NotFound`・`RestrictedContent` は取得不能件数として `unavailableRelatedItems` へ保持し、正常な作品と概要を表示できるようにする。全部の関連プレイリストが取得不能でも件数を残し、空の正常な棚と区別して再試行を案内する。応答全体のGraphQLエラー・通信エラー・未知のunion型は引き続き失敗とする。
- フォローは公開画面のlibrary操作（module 75864 → 70422）と同じ `addToLibrary/removeFromLibrary` の `libraryItemUris` へ対象アーティストURIを渡す。変更後に概要の `saved` を再取得し、一致を確認してから成功とする。アカウント切り替え後の応答は採用しない。

### 紹介文の未提供と検証対象の同一性

2026-09-15の実機比較では、正規のQueenは日本語指定・既定言語の両方で `profile.biography.text` が632文字の文字列、正規のNirvanaは両方でnullだった。後者は言語指定や別のwrapperによる取り落としではなく、確認時の提供元の未提供値である。紹介文が提供される場合はその本文を保持し、nullを架空の文章へ置き換えない。

旧検証は検索結果の英字名との完全一致で最初の項目を選んでいたが、実測では両方とも正規の対象IDと異なるアーティストを選んでいた。表示名の翻訳と同名別アーティストがあるため、固定サンプルの検証には公開ページで確認したURIを使う。製品の検索結果の名前や順位は書き換えない。

比較は `ArtistBiographyProbeTest` の `liveArtistBiographyProbe=true` で行い、キー・型・本文の文字数・ID一致の真偽だけを記録した。原記録はGit管理外の `build/qa/artist-biography-observations.log`。紹介文の本文や認証情報を診断ログへ出力しない。

## 検証

`ArtistCatalogTest` は、64bitのリスナー数、未提供値、代表版の選択、種別、複数ページ、各リンクの保持、フォロー変更と再照会、未確認の書き込み・未知の形状の拒否を合成した応答で検査する。既存の `CatalogNavigationTest` はアーティスト人気曲のページ送りと装飾後の元の順序を維持する。

`ArtistPageAccountTest` に `liveArtist=true` を明示すると、ログイン済み端末で2組のアーティストの概要・人気曲・全ディスコグラフィーを取得し、アルバム、関連アーティスト、ラジオ、提供された参加作品とプレイリストの各先頭を開く。紹介文は原応答と表示モデルの一致を真偽値で検査し、少なくとも1組について提供された非空の本文が保持されることを要求する。未提供の場合もnullとの一致を確認する。読み取り専用でフォローは変更しない。アカウントのフォローを実際に書き換える検証には、対象と一時変更の許可が別途必要。実行結果は `verification.md` へ記録する。
