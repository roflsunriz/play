# 調査知見

- 対象service固有名、音声鍵、PCM、認証情報をsource以外へ残さない。再生位置の進行だけで音声成功とせず、復号後の非無音levelを数値で確認する。
- Gradle wrapper/plugin更新はapp依存だけでなくbuild classpath・plugin marker POMのverification metadataも必要。空のGradle user homeと `--no-configuration-cache` で全OS artifactを生成し、既存hashを削らない。
- OkHttpの更新ではAndroid向けmodule・AARだけでなくJVM向けmodule・JARも依存検証の対象になる。生成したSHA-256をMaven Centralの実ファイルと照合してから通常の依存検証で再ビルドする。
- URI identityはpercent encodingを1回だけdecodeし、`+` を空白へ変えない。不正escape・別identityを拒否する。
- 認証refresh開始後はcancelを受けてもmutex内の `NonCancellable + IO` でrotated token保存まで完了する。失効済みtokenは復元できず、通常loginが必要。
- library通信はnative protobuf契約。未知length fieldは長さを先に読み、その後positionを進める。playlist revision bytesとcollection timestamp整数、create OpList field 1とchange Delta field 2を流用しない。
- 一部旧曲は要求URIと別のplayable URIをmedia keyに返す。`linked_from_uri` が要求URIと一致する唯一候補を選び、先頭要素や曲名一致で代用しない。
- `TransitionPlayer` は2つの標準DRM ExoPlayerを持つ。MediaSession playerを直接castせず、engine別に測定する。license待ちの予備decoder overlapは使わず、同曲seekは再生decoderをmute→seek→fade-inする。
- automixはmetadata 27/28、mode、両曲の原playlist所属、URI/cuepoint型を検証する。要求crossfadeより残時間が短い場合は開始位置を捨てず残区間へ短縮する。
- lyricsはtrack URIで分離し、公式/LRCLIBを切替える。LRCLIBは曲名・artist・album・durationを照合し、別曲を表示しない。歌詞本文をlog/fixture/Gitへ保存しない。
- favoritesの専用URIはrootlistの通常playlistではない。専用factory/TRACK collectionへ接続し、UIへ1回だけ追加する。所属/削除は装飾一覧でなく原本全pageで確認する。
- 実account書込みは明示許可された一時対象だけ。checked journalを保存し、既存playlist/follow/saveを変更せず、追加分の原本不在を確認してからjournalを消す。
- sleep timerのscreen lockはdevice admin + exact alarmが必要。権限付与は本人操作、force-lock testは分離AVDだけに限定する。
- 音声effectはimmutable settingsをaudio threadから読む。PCM16へprocessorを接続し、float/compressed passthrough/offloadを考慮する。meterは本番processor後段に置く。
- capture toolは対象deviceの元proxyと所有adb reverseだけを記録・復元する。強制終了でfinallyが動かない場合はPID/起動時刻/commandと現在設定を照合し、他転送を消さない。
- 公開署名へ移行済み。debug鍵APKを上書きできると仮定せず、同じ専用鍵でtest/mainを署名する。署名不一致で認証dataを消さず、公開APKはnon-debuggable・証明書hash・release checksumを確認する。
- session schema 4は旧2/3から暗号化migrationするが、旧APKは4を読めない。rollback時に認証維持を保証せず、login待機中の実機を再install/instrumentationで中断しない。
- WindowsでSDK/Kotlin cacheのAccessDeniedが出たら環境設定を変える前に昇格PowerShellで同じ検証を再実行する。setup-androidは旧 `tools` でなく `platform-tools` を明示する。
