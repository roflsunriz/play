# 検証手順と結果

## 2026-09-25の結果

### v0.7.0の公開（匿名化エラー報告）

- プッシュ前にOSV-Scanner v2.6.0（配布SHA-256照合済み）で公開DBをローカル照合し、依存592件に該当する既知脆弱性0件を確認した。未処理のDependabot PRはなし。`testDebugUnitTest` 250件失敗0、`lintDebug` エラー0、`assembleDebug`・`assembleRelease` 成功。
- バージョンを0.7.0・versionCode 7へ確定してmainへプッシュし、main CI成功後に同コミットへ`v0.7.0`タグを付け、Release CIも成功した。公開物は`play-0.7.0.apk`とSHA-256ファイルである。
- ダウンロードしたAPKはSHA-256 `f83d05021cf778944f13ebc2bd6c6aa4b8a6e9f6fa351191ef50c44996e67921`が添付検証値と一致、v2/v3署名、専用証明書（`7ff600f4…`が`SECURITY.md`と一致）、debuggableなし、versionCode 7・versionName 0.7.0を確認した。
- 実機への更新と実機での共有導線の確認は未実行。

## 2026-09-24の結果

### 匿名化エラー報告の共有委任

- 再生失敗時に`errorCode`名・ライセンス状態・認証段階・直前64件の再生操作履歴だけを含む匿名化レポートを、利用者の共有操作でのみ外部へ渡せるようにした。例外メッセージ・トークン・URL・曲名・生URIは保持せず、曲特定はSHA-256短縮ハッシュのみ。GitHub投稿はブラウザー／アプリへの委任で、自動送信は行わない。
- JVM250件失敗0（新規`PlaybackErrorReportTest` 4件を含む秘匿化・上限・本文長の検査）、`lintDebug` エラー0、`assembleDebug` 成功。
- 実機でのエラー共有導線の確認は未実行。JVMでは`PlaybackException`自体を構築できない（`SystemClock.elapsedRealtime`がスタブのため）ため、原因連鎖を受ける別経路で検査した。

## 2026-09-16の結果（21:10 JST）

### v0.6.0の詳細内並び替え拡充と横並び化

- プレイリスト詳細の並び替えを8種（追加日が新しい順・古い順、名前の昇順・降順、アーティスト昇順・降順、アルバム昇順・降順）、アルバム詳細の曲並び替えを6種（アルバム順・逆順、名前の昇順・降順、再生数が多い順・少ない順）へ拡充した。並び替えボタンを再生ボタンやお気に入りと同じ操作行へ移動し、狭幅でも同じ行に収まるよう調整した。
- `testDebugUnitTest` 成功（`LibraryPresentationTest` 8件失敗0を含む）、`lintDebug` エラー0、`assembleDebug`・`assembleDebugAndroidTest`・`assembleRelease` 成功。分離AVD（emulator-5554、実機に触れず）で `ContentDetailScreenTest` 7件、`DetailSortStoreTest` 1件が成功。
- バージョンを0.6.0・versionCode 6へ確定してmainへプッシュし、main CI成功後に同コミットへ`v0.6.0`タグを付け、Release CIも成功した。公開物は`play-0.6.0.apk`とSHA-256ファイルである。
- OSV-Scannerは実行環境に用意がなく未実施。依存関係の変更はない。実機の表示確認と実音声確認は未実行。

## 2026-09-16の結果

### v0.5.0の公開

- ハンバーガーメニューに「GitHubからダウンロード」を追加し、バージョンを0.5.0・versionCode 5へ確定してmainへプッシュした。main CI成功後に同コミットへv0.5.0タグを付け、Release CIも成功した。
- 初回実行はMaven Centralの一時的な403で失敗したため、失敗分だけ再実行して成功した。製品・設定の変更は行っていない。
- 公開物はplay-0.5.0.apkとSHA-256ファイルで、本文はCHANGELOGの抽出結果と一致した。ダウンロードしたAPKは専用証明書、v2/v3署名、versionCode 5を確認し、SHA-256はbf1317870a85971d6d799a1e8a9b03607476e893ff183e63cf04dce314b33877で添付検証値と一致した。
- 分離AVDで新規instrumented 2件が成功し、メニュー表示を画像で確認した。JVM 246件・lint・ビルドも成功。
- 実機への更新は接続中の実機がなく未実施。OSV-Scannerによる監査も実行環境に用意がなく未実施。
## 2026-09-15の結果

### v0.4.0の公開と実機更新

- プッシュ前にOSV-Scanner v2.6.0（配布SHA-256照合済み）で公開DBをローカル照合し、依存481件に該当する既知脆弱性0件を確認した（DB更新は2026-09-16）。
- `testDebugUnitTest lintDebug assembleRelease` が成功し、バージョンを0.4.0・versionCode 4へ確定してmainへプッシュした。main CI成功後に同コミットへ`v0.4.0`タグを付け、Release CIも成功した。
- 公開物は`play-0.4.0.apk`とSHA-256ファイルで、本文がCHANGELOGの抽出結果と一致した。ダウンロードしたAPKは専用証明書、デバッグ無効、versionCode 4を確認し、SHA-256は`cb2593c53df4273d962a09adf1418012e465c1b9f6e1d76b435f932fd3037acc`で添付検証値と一致した。
- 実機SH-R80Pへデータ保持更新し、端末から取得したAPKとも一致。通常起動後の保存ログイン・ライブラリ・「お気に入りの曲」表示とログイン案内0件を確認した。バージョン番号・公開タグの変更はこの記録が最後である。

### 歌詞のLRCLIBタブ追加

- 拡大プレイヤーの歌詞に公式配信とLRCLIBの切替タブを追加した。LRCLIB側は曲名・歌手名・アルバム・再生時間（±10秒）を照合し、不一致・未配信・演奏のみ・空応答は未配信として扱う。同期データがある場合は公式と同様に行強調・自動追従・行シークする。本文のログ・fixture・Git保存はしない。
- JVM246件失敗0、`lintDebug` エラー0、`assembleDebug`・`assembleDebugAndroidTest` 成功。分離AVD（emulator-5554、実機に触れず）で `LyricsTabsTest` 3件、`LyricsPanelTest` 7件が成功。実機の表示・実データ確認は未実行。

### 復号結果の保持可否と読込拡大、永続鍵probe

- 復号結果（平文PCM）のメモリ・ディスク保持は行わない。鍵は端末DRM内に留まり、復号音声を取り出す正規の経路がなく、保存は製品の暗号化データのみ保存する契約に反する。等価の正規手段として、暗号化キャッシュ（設定化済み）と再生中トラック全体のRAM保持（読込制御の最大バッファを50秒から10分へ拡大、開始・再開のしきい値は既定のまま）を用意した。
- 永続（オフライン）ライセンスのprobeを診断版で実機実行した。2回とも取得POST自体が429に巻き込まれ、発行可否の判定には至らなかった。公開Web配信コードにも永続・オフライン鍵の仕組みはなく、参照実装は都度取得のみを使う。推測で永続鍵基盤は作らず、診断コードは除去した。
- 再試行の所有権を自前の取得処理へ一本化し、プレイヤー側の即時再送は`DrmRetryPolicy`で止めた。JVM240件失敗0（方針・再試行・間隔・原因連鎖の検査を含む）、`lintDebug` エラー0。分離AVDの遷移回帰は読込拡大後に成功。実音源の長時間聴取は未実行。

### 音楽キャッシュ上限の実機反映

- `testDebugUnitTest lintDebug assembleRelease` が成功し、専用署名の `build/outputs/play-updated.apk` を生成した。証明書は公開識別子と一致、非debuggableを確認。バージョンは0.3.0のまま。
- 実機SH-R80Pへ `install -r` でデータ保持更新し成功。端末から取得したAPKのSHA-256は `9BFEC34DF9AD9B53484EDFE4D8513D958851EDBC2E73316D63A21E026CAF5E9D` で配布ファイルと一致した。
- 通常起動で保存ログインとプレイリスト一覧（「お気に入りの曲」含む）を確認し、再生設定に「音楽キャッシュの上限」20 GBの行が表示されることを画像で確認した。既存設定の変更はない。

### 音楽キャッシュ上限の設定化

- 復号結果のメモリ・ディスク保持は行わない。鍵は端末のDRM内に留まり、復号音声を取り出す正規の経路がなく、保存は規約・契約違反になる。保存対象は従来どおり暗号化配信データのみで、その上限を再生設定から1〜64GB（初期値20GB）へ変えられるようにした。再起動後に適用される。
- JVM238件失敗0、`lintDebug` エラー0、`assembleDebug`・`assembleDebugAndroidTest` 成功。分離AVD（emulator-5554、実機に触れず）で設定画面の新規キャッシュ検査を含む `PlaybackSettingsScreenTest` 6件、`PlaybackTransitionStoreTest` が成功。実機の表示・容量確認は未実行。

### シーク遅延の修正（重なり方式の撤去）

- 実機でシークが10秒以上止まることを再現した。シーク自体は即時に受け付けるが、シーク時クロスフェードの予備デコーダー重なりが新しいライセンス取得待ちで止まっていた。設定OFFの直接シークは1秒以内に完了し、フェード長の問題ではないことを確認した。
- 同曲シークは再生中のデコーダーで消音と同時にシークし、設定秒数でフェードインする方式へ変更した。予備デコーダーを起こさず、新しいライセンスも要求しない。位置は直接シークと同様に即時に飛ぶ。
- 修正版の実機でシーク時クロスフェードONのまま1秒以内に飛ぶことを確認した。JVM238件失敗0、`lintDebug` エラー0、`assembleDebug`・`assembleDebugAndroidTest` 成功。分離AVDで `PlaybackTransitionsTest` 4件が成功。聴感の最終確認は未実行。

### 詳細内並び替えの追加

- プレイリスト詳細にタイトル・作成者・アルバム・追加日の並び替え、アルバム詳細にアルバム順・タイトル・再生数の並び替えを追加した。初期値はプレイリストが追加日の新しい順、アルバムがアルバム順で、各指定値は専用保存へ書き込み再起動後も再現する。
- 再生数・曲順はカタログ応答の既存項目（`playcount`の数字文字列、`trackNumber`/`discNumber`の整数）を読み取る。追加日はプレイリスト項目のタイムスタンプ（書込時と同じミリ秒）と保存コレクションの追加時刻（秒）を使う。いずれも実応答・自前の書込経路で裏付けし、欠落や不正値は無視して元の順序を保つ。
- JVM238件失敗0、`lintDebug` エラー0、`assembleDebug`・`assembleDebugAndroidTest` 成功。分離AVD（emulator-5554、実機に触れず）で `DetailSortStoreTest`、`ContentDetailScreenTest` 7件、`CatalogJsonTest` 6件、`PlaylistEditorScreenTest` 5件、`HomeScreenTest` 10件が成功。実機の表示確認は未実行。

### シーク・曲切替時のライセンス429の修正

- 連続再生は成功するがシークや曲切替で`ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED`になることを、診断版を実機へ入れて再現した。原因はライセンスのHTTP 429（空本文、時に`Retry-After`付き）で、3曲前後の連続取得で制限に入り、Exoの即時再試行も全滅していた。認証の期限・更新とは無関係（残り52分の再利用中に発生、再取得しても変わらない）ことを確認した。
- `LicenseHttpClient`は429/503を認証不良と区別し、同じ要求・認証のまま送り直す。`Retry-After`（秒数・HTTP日付、最大60秒）に従い、なければ10秒・30秒・60秒で最大3回送り直す。要求全体は`LicensePacer`で4秒以上空ける。取得不能が確定した重なりでは切り替え先を諦め、鳴っている曲を止めず切り替えの断念を通知する（`playback_switch_busy`を11言語へ追加）。
- 診断版の実機で429の連続→60秒超の待ち→HTTP 200での回復と切替完了を確認した。以降の最終版（診断除去）でも連続切替で即時エラーを出さず待機することを確認した。JVM235件失敗0、`lintDebug` エラー0、`assembleDebug`・`assembleDebugAndroidTest` 成功。実音源の長時間聴取と放棄通知の目視は未実行。

### アクオス実機への反映（4件の未公開修正）

- `testDebugUnitTest lintDebug assembleRelease` が成功し、`tools/sign-release.ps1` で専用署名した `build/outputs/play-updated.apk` を生成した。証明書SHA-256は `SECURITY.md` の公開識別子と一致、debuggableでないことを確認。バージョンは0.3.0・versionCode 3のまま（今回の4修正はUnreleasedへ記録）。
- 実機SH-R80P（serial末尾は記録しない）へ `install -r` でデータ保持更新し成功。署名不一致時のデータ消去は行っていない。端末から取得したAPKのSHA-256は `C55AB2B3DBD0090AB4BE82A0F395AF61565CC07BBB64F22BF27F8B4015713848` で配布ファイルと一致した。
- 通常起動（`am start`）でプロセス起動を確認した。端末がロック画面のため画面内の確認は未実施であり、利用者に解除後の確認を依頼する。検証用の一時ファイルは端末から除いた。

### シーク時クロスフェード設定の追加

- 再生設定にシーク時クロスフェード（切替＋1〜12秒、初期値無効・3秒）を追加し、11言語の文言を整備した（日本語・英語以外は本作業での新規訳）。保存形式は版1のまま新キーを追加し、旧保存データは無効・3秒として読む。
- 有効時は同曲内シークを予備デコーダーで重ね、旧位置を鳴らしたまま同キューを指定位置から開始する。前後の残りで短縮し、500ms未満の移動・一時停止中・スリープ中は直接シークのまま。重なり開始は古いフェードを止めてエンベロープを全音量から始める。
- 新規JVM回帰（往復・旧形式の既定値・秒数境界）と、分離AVD（emulator-5554、実機に触れず）での `PlaybackTransitionsTest` 4件（新規シーク重なり含む）、`PlaybackSettingsScreenTest` の新規切替検査、`PlaybackTransitionStoreTest` が成功。JVM229件失敗0、`lintDebug` エラー0、`assembleDebug`・`assembleDebugAndroidTest` 成功。実音源での聴感確認は未実行。

### 選曲切り替えのクロスフェードが一旦無音になる問題の修正

- 一覧から別の曲を選ぶと、先にaが無音になってからa→bのクロスフェードが再生されることを報告された。`handleSetMediaItems` の重なり分岐はaを鳴らしたままbを開始するが、直後の`play()`→`startPlayback()` がbの未再生だけを見てエンベロープを0へ初期化し、両デコーダーを消音してから非同期にフェードインし直していたことが原因。
- `startPlayback()` は重なり有効中は両エンジンの再生だけ再確認して音量へ触れず、全音量で再生中かつフェードも動作中でなければ再生状態だけ再確認して戻るようにした。フェード反転（一時停止直後の再開）は従来どおり現在の音量から戻す。
- 新規回帰 `replacingTheQueueMidSongCrossfadesWithoutSilencingBothDecoders` は修正前 `total=0.0` で失敗、修正後、分離AVD（emulator-5554、実機に触れず）で `PlaybackTransitionsTest` 3件、`PlaybackTransitionFailureTest` 5件、`ExpandedPlayerScreenTest` 5件が成功。JVM228件失敗0、`lintDebug` エラー0、`assembleDebug`・`assembleDebugAndroidTest` 成功。実音源での聴感確認は未実行。

### 歌詞の表示場所を拡大プレイヤーへ移動

- 再生していない曲の歌詞を見せないよう、`ContentDetailScreen` の歌詞slotを除去し、`ExpandedPlayerScreen`（縦・横両配置）の操作部の下へ移動した。`LyricsRoute` は再生中の曲URIで取得し直し、プレイヤーを離れれば本文も破棄される。
- `PlaybackSettingsScreenTest` の歌詞slot検査を拡大プレイヤー接続へ書き換えた。`LyricsPanelTest`・`LyricsApiClientTest` は対象外のため不変。
- JVM228件が失敗0、`lintDebug` エラー0、`assembleDebug` と `assembleDebugAndroidTest` のビルドが成功。書き換えた画面テスト自体は実機・AVDで未実行であり、拡大プレイヤーでの歌詞表示・追従・行シークは実機検証時に確認すること。

### 初回ログイン直後のDRMライセンス取得失敗の修正

- 症状はフレッシュインストール直後と初回ログイン直後に`ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED`となり、10秒以降が復号できない一方、時間経過で再生できる場合があること。再生派生認証の初回取得が複数往復（sessiontransfer→OTT→公開bundle最大8MB→TOTP→token→client-token）のため、DRMスレッド上の`runBlocking`取得が最初の暗号境界に間に合わないことが原因と特定した。キャッシュ後は成功するため時間経過で解消したように見える。
- `LicenseHttpClient`が認証例外（再生用認証・端末認証・未ログイン）をNETWORKへ変換して原因を消していたため、そのまま伝播させるようにした。`AuthenticatedDrmCallback`は認証失敗も原因を保持して包装し、秘密値は保持しない。包装関数はJVMで差し替え可能にし、端末依存の`DataSpec`生成は製品の既定経路に残した。
- `PlayViewModel`はログイン完了直後（初期起動・通常ログイン・従来ログイン）と再生開始3経路（`play`・`playDetailTrack`・`seekLyrics`）で`playbackAuthorization.prepare()`を接続した。ログイン直後は裏で事前取得し、再生開始前は取得待ちしてから`localPlayback.play()`する。事前取得の失敗時は再生自体を止めず、DRMコールバックの401再取得に任せる。ログアウト時は事前取得を取り消す。
- JVM228件が失敗0（新規5件：認証例外の非マスク、コールバックの原因保持3件、事前取得の再利用）。`testDebugUnitTest lintDebug assembleDebug`が成功、lintエラー0。実機のフル音声確認（`AudioOutputTest(fullTrack=true)`）は未実行であり、実機検証時に10秒以降の音声と自然終端を確認すること。

### v0.3.0の公開

- ユーザーのプッシュ・リリース許可を受け、公開済みv0.2.0以降の追加機能と修正をv0.3.0へまとめた。versionCodeを3へ更新し、変更履歴を日付付きの節へ移した。README、署名方針、更新手順を現行の導線と照合した。
- バージョン更新後の`testDebugUnitTest lintDebug assembleRelease`が成功（55秒）。APKメタデータはversionName 0.3.0、versionCode 3、既存applicationIdを保持。223件のJVM回帰、既存の実機機能検証に加え、公開DBの依存監査を再実行し481依存・該当0件を確認した。`build/qa/release-030-build.log`、`release-030-osv.json`。
- 公開対象は`66b918f09b4a7e2217ac46eb23c429c1bdffd027`。[main CI](https://github.com/roflsunriz/play/actions/runs/34948350157)成功後に同コミットへ`v0.3.0`タグを付け、[Release CI](https://github.com/roflsunriz/play/actions/runs/34948882672)も成功した。[正式リリース](https://github.com/roflsunriz/play/releases/tag/v0.3.0)にAPKとSHA-256ファイルを公開し、本文がCHANGELOGの抽出結果と一致した。
- 公開後にダウンロードしたAPKは専用証明書、デバッグ無効、versionCode 3を確認。SHA-256は`507011da51e468f4148b34ae00800c16eb8c3f1d1949611283cf17872839a7d8`で添付検証値と一致した。実機SH-R80Pへデータ保持更新し、端末から取得したAPKとも一致。「お気に入りの曲」表示1件、ログイン案内0件を確認した。`build/qa/release-030-public/`、`release-030-device.xml`。

### 特定の検索語で全体の取得に失敗する問題

- 実機SH-R80Pで「トリッカル」の全件/プレイリストが`Profile response belongs to a different user`、「インターネット」の全件/ジャンルが`Unexpected catalog entity`になる4条件を修正前に再現した。`build/qa/search-regression-before.log`。再ログインや保存データの変更は行わず、保存済み認証を使った。
- 原因は所有者URIとusernameのエンコード差、およびGenre結果の「お気に入りの曲」ナビゲーションカードだった。IDの1回decodeと既存のお気に入り経路への変換で修正した。別ユーザー・二重decode・不正escape・未知の種類は拒否する。プロフィール取得まで通るRepositoryの合成回帰と、型・URIの個別回帰を追加した。
- Repository経由の「jazz」「lo-fi」「トリッカル」「インターネット」×7分類、報告4条件とお気に入り詳細の読み取りが実機で成功。2件、48.831秒。`build/qa/search-regression-after.log`。前回のカタログ直呼びの試験では所有者名取得を通っておらず、この範囲を検査できていなかった。
- 検索画面の初回検査では、返されたお気に入りカードを表示側の種類フィルターが落とす問題も検出した。GENRESの該当URIだけを保持するよう訂正し、4条件の画面表示からカード選択・保存曲詳細まで実機1件成功（14.168秒）。検索結果2画面と詳細の画像を閲覧した。`build/qa/search-regression-ui-final.log`。分離AVDの通常7分類と特殊カード表示は2件成功（7.652秒）、`search-regression-ui-avd.log`。
- JVM223件が失敗0。`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease`成功、lintエラー0・既存警告5件。追加UIテストの必須callback引数漏れは修正して再実行した。最終ログは`build/qa/search-regression-verified-build.log`。依存追加なし、OSVの公開DBローカル照合は481依存・該当0件（`search-regression-osv.json`）。
- 専用署名・デバッグ無効の`build/outputs/play-updated.apk`を実機へデータ保持更新し、起動後の「お気に入りの曲」表示とログイン案内0件を確認した。端末から取得したAPKとのSHA256一致も確認した（`fa7b2d97191ad13ce8965c93cb25c75f83f30145e324b9b42972f7101a56762b`）。テストAPKと今回の検証画像を端末から除いた。バージョン番号・公開タグは変更していない。

### 再生遷移・同期歌詞・アーティストページの追加

- 保存済みログインを保持して実機SH-R80Pで検証した。同期歌詞は時刻付き55行の取得が成功し、本文は記録しなかった。アーティストの全作品、人気曲、関連アーティスト、ラジオ、参加作品・プレイリストの遷移が成功した。英字の名前一致で同名別アーティストを選んでいた旧検証を正規URIへ変更し、紹介文が提供される場合の完全保持と、提供元がnullの場合の一致を確認した。
- 明示許可した未フォロー1組だけを一時フォロー・解除し、原本コレクションの全URI集合と既存アーティスト集合の保持、ジャーナル解除まで成功した。既存フォローは変更していない。`build/qa/advanced-live-full.log`の`ArtistFollowAccountTest`、紹介文の型・文字数比較は`artist-biography-observations.log`。本文はログに展開していない。
- 通常フェード・クロスフェードは実音源で成功（58.031秒）。30秒以降の音声、2つの再生位置に対応する非無音PCM、ゲイン和1、一時停止・停止による両デコーダー停止を確認した。最終の重畳RMSは約0.186/0.293、ゲインは約0.808/0.192。端末全体の音量は変更せず、再生設定とEQを復元した。`normal-transitions-media-clock-final.log`と同`metrics.log`。
- Automixは型27の対象判定・型28の実cuepointを使用し、設定1秒/12秒の解決、対象外404、原本所属の検査に成功した。実音声でも開始位置・テンポ補正・両音声の重畳・通常速度への復帰・停止を確認した（21.554秒）。outcue183867msに対し実ファイル終端186209msだったため、要求5625msを実残時間2342msへ短縮した。制御が遅れた場合は入場位置をテンポ比で補正し、固定100msで棄却しない。最終実測は開始out183873/in7831、重なり2336ms、速度1.0406504→1.0。`automix-media-clock-final.log`、`automix-media-clock-metrics.log`。
- 音声検証器の`TeeAudioProcessor`はEOSでsink.flushを呼ぶため、端末に渡した音声が鳴り終わる前にRMSを0へ消していた。`MediaTimeLevelMeter`に置き換え、公式の`StreamMetadata.positionOffsetUs`とフレーム数で媒体時刻を付け、同じperiod・generationの現在位置に対応する数値窓を参照する。EOSで窓を消さず、シークで分離する。今回の実音源はencoderDelay/encoderPaddingとも0、period照合も成功した。波形・音声鍵は保存していない。
- 分離AVDでは歌詞7件（自動追従、ドラッグ、アクセシビリティ、行シーク、未提供等）、アーティスト7件、再生設定4件、バックグラウンド再開を含む再生操作、保存・PCM処理が成功した。新しい計測器5件＋低い横画面の設定4件は9件成功（9.499秒）。準備保持、短い曲末尾、遅延補正、FGS失敗などの追加回帰5件も成功（17.102秒）。`advanced-ui-final-ja.log`、`time-meter-settings-avd.log`、`transition-regression-final.log`。
- 最大12秒のスリープタイマーは、OSアラーム→減衰→両エンジン停止→消灯と、先に自然終端した場合の消灯だけの動作が成功した。歌詞・異常時停止と併せて11件成功（154.549秒）。消灯権限の自動付与と消灯試験はAVDだけで行った。`advanced-lyrics-sleep-avd.log`。
- 日本語・低い英語横画面・狭幅アラビア語で画面と操作を確認した。アラビア語18件成功（49.412秒）。日本語の歌詞・再生設定・アーティスト情報のPNGを閲覧した。横画面の設定試験は、遅延配置されたノードを直接押す方法をキーによる表示と完全可視性確認へ訂正し、設定4件の成功まで確認した。計測窓や停止位置の期待値を緩めて成功としたものではない。
- JVM219件は失敗0。`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease`が成功。Kotlin FIRのlint内部例外は編集中ファイルとの競合が疑われたため、編集を止めた単独再実行で成功し、検査は無効化していない。依存変更なし、公開DBによるローカルOSV照合は481依存・該当0。`advanced-time-aligned-build.log`、`advanced-lint-retry.log`、`advanced-osv.json`。
- 最終の表示単位は単複に依存しない秒の表記へ整え、lintはエラー0・既存警告5件。`advanced-delivery-build.log`で最終ビルドが成功した。専用署名・デバッグ無効の`build/outputs/play-updated.apk`を実機へデータ保持更新し、ログイン要求なしで起動、メニューから再生設定を開いて各項目を確認した。端末から取得した最終APKとのSHA256一致を確認し、テストAPKと検証専用キャッシュ・原応答控えを端末から除いた。バージョン番号と公開タグは変更していない。

### 保存操作・検索拡張・認証更新・スリープタイマー

- 実機SH-R80Pで、変更前から保存されていた認証情報が`400 invalid_grant`として拒否されることを再現した。検索語に関係なく更新要求で停止していた。`SessionManager`の更新通信と保存をキャンセル不可の区間にし、分離AVDの`SecureSessionStoreTest`7件で、検索キャンセル後の保存・新しいstoreからの連続更新と、ログアウトしたアカウントを復活させないことを確認した。`build/auth-cancellation-avd.log`。
- 失効済み認証を復元できたとは扱わず、ユーザーが通常ログインを一度実施した。その後、実機の指定3検索語×7カテゴリ、曲詳細・アーティスト・ソングラジオ、番組・エピソード・ジャンル詳細が成功。初回に見つかったジャンルpreviewの`pagingInfo`欠落は、公開ソースの`items/totalCount`と続きの`browseSection`を区別して修正した。`build/qa/features-live-roundtrip.log`のカタログ3件。
- 明示許可後、未登録の曲・アルバム各1件を実アカウントのお気に入りへ一時追加し、原本再取得・ライブラリ表示・専用お気に入り一覧を確認して削除した。元のコレクションの全URI集合が一致し、検証ジャーナルも解除された。新規の非公開プレイリストで曲・アルバムの追加/削除と所属チェック、名前/説明/画像の保持・削除を検査し、検証リスト削除後の原本rootlist一致も確認した。実機5件すべて成功、106.006秒。既存のお気に入り・プレイリストは維持した。
- 実機の`LibraryAccountTest`による期限切れからの自動更新と保存済み情報による連続更新は2件成功（3.335秒）。`build/qa/auth-live-refresh.log`。再ログインの待機中には実機の再インストール・instrumentationを行っていない。
- 分離AVD（API36、emulator-5554）で`SleepTimerIntegrationTest`3件が成功（128.905秒）。実OSの1分アラームからMedia3の音量中間値・停止・元音量への復元・消灯を確認し、自然終端済みの場合は終端状態と位置を維持したまま消灯することも確認した。OS権限の自動付与と消灯はAVDだけに実施した。合成無音源による検査であり、実音源の耳によるフェード聴取とは区別する。`build/qa/timer-avd.log`。
- 日本語縦画面の保存操作・7カテゴリ・タイマー・一覧キャッシュ・プレイリスト編集は27件成功（77.485秒）、アラビア語320×600dpは新画面13件成功（34.053秒）。古いテストAPKで発生した「通常プレイリスト削除後は一覧が空」という失敗は、新仕様の専用お気に入り1件とディスク原本の空を別々に検査する最新APKで再確認した。`build/qa/features-ui-ja.log`、`features-ui-rtl.log`。
- 英語800×360dpは13件成功（28.718秒）。メニューの末尾へスクロールして完全に表示されたアーティスト操作を押す検査にし、低いタイマー画面では見出しを畳んで時刻入力と確定ボタンを表示した。日本語/RTLの選択画面、横画面のメニューとキーボード表示中のタイマーのPNGを実際に閲覧した。プレイリスト選択の検索欄は短いラベルと検索アイコンへ変更した。`build/qa/features-ui-landscape-final.log`と`features-*.png`。
- JVM190件は失敗0。`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease`が成功した。lintはエラー0、従来の更新候補/KTX提案5件。依存変更なし。OSVは公式配布のSHA256照合済みv2.5.1で公開DBを取得してローカル照合し、481依存・該当0件。`build/features-final-build.log`、`build/qa/features-osv.json`。Windowsの共有Kotlin領域の拒否は昇格した同一検証で解決し、既存の一時Gradleレポートの衝突は生成HTML1件だけを除いて再生成した。
- 画面調整後も`lintDebug assembleDebug assembleDebugAndroidTest assembleRelease`が成功（`build/features-artifacts.log`）。専用公開署名の`build/outputs/play-updated.apk`を実機へデータ保持更新した。debuggableでないこと、公開証明書の一致、端末から取得した最終APKと署名済みAPKのSHA256一致を確認し、通常起動時に「お気に入りの曲」が1件、再ログイン案内が0件であることを確認した。リリースタグやバージョンは今回変更せず、変更履歴はUnreleasedへ記録する。

### 旧版の曲・アルバムで再生開始に失敗する問題

- 実機SH-R80Pの保存アルバムQueen Jewelsは全16曲が旧実装で`Audio manifest is missing`になった。ユーザー指定の保存プレイリスト内Fearless Pt. IIも同じ失敗で、`AudioOutputTest`から`ERROR_CODE_IO_UNSPECIFIED`を再現した。検索の同名曲は別URIで正常に再生できた。ログは`build/qa/streaming-library-jewels-before.log`、`streaming-daily-before.log`、`streaming-daily-audio-before.log`。
- 両作品の実応答は再生可能版を別URIで返し、`item.metadata.linked_from_uri`に要求URIを保持していた。形式10は存在した。元URIに対応する唯一の再生可能版を選ぶよう修正し、無関係・URI不一致・対応欠落・null・複数候補・アルバムURIを拒否する回帰を追加した。正常な別版のテストは修正前に失敗し、修正後は既存の認証更新・キャッシュ・配信先検証を含むJVM162件が成功した。
- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease`が成功。lintはエラー0・既存警告5件。初回はWindowsのKotlin共有キャッシュ権限、別の全検査ではGradleのレポート保存時のファイル競合を検出し、環境設定を変えず昇格・逐次再実行で成功した。最終ログは`streaming-final-build.log`。
- 修正版の`StreamingAccountTest`で対象プレイリストの曲とアルバム全16曲が成功（19.947秒）。最初の再検査ではプレイリスト曲の通信に一時的なConnectExceptionが発生したため、成功扱いにせず再実行した。`streaming-resolution-after.log`、`streaming-resolution-repeat.log`。
- 元のFearless Pt. IIのURIで`AudioOutputTest(fullTrack=true)`が198.395秒で成功。背景へ移って自然終端まで再生し、冒頭15/15・中盤19/19・終盤20/20区間に音声があった。`LivePlaybackTest(trackUri=元URI)`も18.961秒で成功し、90秒へのシーク、一時停止・再開、自然終端、前後の曲、曲/リストのリピート、シャッフル、元URIの保持を確認した。`streaming-daily-full-audio.log`、`streaming-daily-amplitudes.log`、`streaming-daily-controls.log`。
- Queen Jewels先頭曲の元URIでも`AudioOutputTest(fullTrack=true)`が275.354秒で成功し、約4分25秒の背景再生が自然終端へ到達した。冒頭15/15・中盤19/19・終盤20/20区間に音声があった。`streaming-jewels-full-audio.log`、`streaming-jewels-amplitudes.log`。全16曲は配信情報の解決、音声完走はこの代表曲を検査している。
- プッシュ前監査は公式配布物とのSHA-256一致を確認したOSV-Scanner v2.5.1と同日更新の公開DBで実行し、依存481件に該当する既知の脆弱性0件。`streaming-osv.json`、`streaming-osv.log`。README・更新手順・認証とキャッシュの運用を照合し、旧版を使う再現手順を更新した。
- 専用証明書で署名した非debugの`build/outputs/play-0.2.0-relink.apk`を実機へ上書きし、通常起動後の保存ログイン・ライブラリ・対象プレイリスト64曲の保持を確認した。2026-09-15 13:47:11更新、versionName 0.2.0の未リリース修正版。SHA-256は`da462893cb38b6f0f649a395c1388df1b4b064066e4da2176349c3f42b3798cc`。署名検証ログは`streaming-release-sign.log`。既存データの消去、通常参照アプリの変更、既存プレイリストの書き換えは行っていない。
- 通常版の対象プレイリスト画面から5曲目のFearless Pt. IIをタップし、実際に再生・一時停止した。MediaSessionは89,251msでPAUSED、error=null。画面も対象曲と再生進行を確認した（`streaming-release-playing.png`）。再生中はuiautomatorのidle待ちが失敗したため、その古い階層を再生確認に使わず、画面画像とMediaSessionで検証した。
- 最初のmain CIはSDKセットアップで`Failed to find package 'tools'`となり、Gradle実行前に失敗した。[使用中アクションの入力定義](https://github.com/android-actions/setup-android/blob/v4.0.1/action.yml)と実ログを照合し、通常・リリース両ワークフローへ`packages: platform-tools`を明示した。cmdline-tools、SDK Platform、Build Toolsの導入は維持する。

## 2026-09-13の結果

### 未コミットの診断試作の整理

- 残っていた差分は追跡済み4ファイルと未追跡26ファイルで、終了した参照版の通信観測・認証比較の試作だった。公開済みの製品実装にコミット漏れはなかった。30原本と追跡済みファイルのGit原本・差分をローカルへ保管し、全ファイルのバイト一致・SHA-256を確認してから試作を外した。復元方法は`how-to-update.md`、保管先とハッシュは同日のsessionメモに記録した。
- 継続利用する通信記録ツールの改善を残し、正常・失敗ライセンス応答の本文を保存しないこと、再生メタデータの記録と集計、認証・対象外通信の除外を追加検査した。従来の認証ヘッダー除去・原通信維持・fixture検査を含むPython6件が成功し、変更した3スクリプトの構文検査も成功した。
- 整理した通常の作業ツリーで`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`が24秒で成功。JVM160件は失敗・スキップ0件、lintエラー0件・既存警告5件だった（`build/qa/cleanup-build.log`）。アプリ・通常ビルド設定・依存・CI定義は整理前の公開済みコミットと差分なしで、端末への再インストールや実アカウントの再検証は行っていない。
- プッシュ前に公開DBをローカル照合し、依存481件の該当する既知脆弱性は0件だった（`build/qa/cleanup-osv.json`、DB更新は同日03:22:30）。反映対象7ファイルに秘密鍵や認証トークンの埋め込みはなく、通信原本・署名鍵・APK・復元用アーカイブをGitへ追加していない。

### プリアンプ・30バンドEQ・保存スロット（0.2.0）

- メニューから開く音質設定を、本番のMedia3 PCM経路と端末内保存へ接続した。プリアンプ±10dB、25Hz〜20kHzの30帯域（±12dB、0.5dB刻み）、7プリセット、名前付き5スロットの適用・上書き・名前変更・削除を確認した。日本語を含む11言語・46文言を整備した。
- 診断WIPを除いた構成でJVM160件が成功。30中心それぞれの±6dB周波数応答、±10dBプリアンプ、左右独立、フラットの同一性、リアルタイム変更、flush、複数サンプルレートを検査した。debugと最適化したreleaseのビルド・lintが成功し、新規警告は解消、残るのは従来の5件（`eq-core-build.log`、`eq-full-build.log`、`eq-final-build.log`）。
- 分離AVDのStore6件、Media3 processor5件、Service/Controller3件が10.880秒で成功。自動保存だけによる復元、5スロットの更新、名前のUnicode境界、破損・未知形式の復旧、IO失敗時の保持・再試行、画面スレッドのIOが0件であることを確認した（`eq-core-avd.log`）。
- 実際のメニューと画面復帰を含む11件が35.366秒で成功。音質設定で変更・保存した後に元のライブラリ位置へ戻れる（`eq-route-avd.log`）。音質画面8件は日本語1280×2856で44.571秒、英語800×360で44.217秒、アラビア語960×1800で47.317秒の成功。全30帯域、7プリセット、5スロット、確認・取消、IME表示下の保存ボタン全体、保存失敗表示を検査した（`eq-ui-ja.log`、`eq-ui-en-low.log`、`eq-ui-ar.log`）。
- RTL画像でdB符号が数値の後ろへ回る問題を見つけ、数値トークンの方向を固定した。実glyph位置で符号が数字の前に描かれる検査と、全帯域の操作が12.708秒で成功した（`eq-rtl-signs.log`）。画像は`build/qa/eq-images/`。テスト側の支持テキスト取得はunmerged treeを使い、検証期待値は維持した。
- 実機の`LiveEqualizerTest`が70.947秒で成功。同じ楽曲の16〜20秒について、設定を−6dBにすると本番processor後の音量も−6dB（許容±0.15dB）になることを確認した。さらに全30帯域を有効にして10秒以降の音声を確認した（`eq-live-gain.log`）。
- `AudioOutputTest`を`liveAudio=true, fullTrack=true, thirtyBands=true`で実行し、321.492秒で成功。30バンドを有効にした背景再生が自然終端へ到達し、冒頭15・中盤19・終盤20の全検査区間で音声があった（`eq-full-audio.log`、`eq-audio-amplitudes.log`）。音声や鍵は保存せず、終了時に元の音質設定へ戻した。
- 実機画面でもプリセット適用→保存→プロセス再起動→適用→名前変更→上書き→削除を操作し、音質設定ファイルと画面の両方で確認した。自分で作った`EQCheck`スロットだけを使い、最後は元のフラット・オフ、全スロット空の状態へ戻して再起動後も一致した。
- 専用署名のrelease APKを検証し、分離AVDで旧署名→移行APK→通常release APKの上書きがすべて成功した。暗号化した合成ログイン、保存ライブラリ、プリアンプ−3.5dB、30帯域、5番目を含む全スロットを画面で確認した。接続失敗の通知は、移行検証中にAVDの通信を止めた条件によるもので、保存一覧は維持された（`eq-upgrade-fixture.log`、`eq-upgrade-*-state.json`、`release-upgrade-*.png`）。
- プッシュ前に依存477件を公開DBとローカル照合し、該当する既知脆弱性0件だった（`eq-release-osv.json`）。署名鍵はGit管理外とし、共用の開発秘密鍵は公開・外部送信していない。
- 専用署名鍵・パスワードのSecrets登録は、送信先`roflsunriz/play`を含むユーザーの明示承認後に実施した。4つのSecretsの登録を確認し、CIを検証→署名・証明書照合→公開へ接続した。秘密値は標準入力へ改行なしで渡し、表示・Git管理していない。
- 初回main CIでは、既存ローカルキャッシュが省略していた親POM/BOMの検証値不足を検出した。空のGradle user homeで全依存を解決し直して4分35秒で成功、3OSのAAPT2照合も成功した。既存783アーティファクトのハッシュは変更・削除なしで、不足9ファイルだけを生成した。追加後の依存481件の再監査は該当0件（`eq-cold-verification.log`、`eq-cold-platforms.log`、`eq-cold-osv.json`）。
- 修正後の[main CI](https://github.com/roflsunriz/play/actions/runs/34760196213)は9分47秒、[リリースCI](https://github.com/roflsunriz/play/actions/runs/34760952374)は7分37秒で成功した。タグ`v0.2.0`は`fda41eedbdc3b4697cfc42a37e80909080ce2462`を指し、署名・証明書照合・APKとチェックサムの公開まで完了した。[公開リリース](https://github.com/roflsunriz/play/releases/tag/v0.2.0)は同日22:57:55（日本時間）。
- 公開済み`play-0.2.0.apk`（2,884,599バイト）をGitHubから取得し、添付チェックサム、GitHubのasset digest、署名を照合した。SHA256は`17fddf3ae9bec29173396e36825b4b7c0177bb525a9b940977d64555fc641438`、証明書は`SECURITY.md`の専用署名と一致した。versionCode 2、versionName 0.2.0、minSdk 24、targetSdk 37で、debuggableではない。
- 実機のPlayの署名移行と上書き更新についてユーザーの明示承認を受け、移行APK→公開APKを`install -r`した。認証・データを消さずに両更新が成功し、同日23:01:27の実機APKは公開ファイルと同じSHA256だった。保存ログイン・ライブラリ・元のフラット/オフ設定を維持し、新しい検索語によるオンライン検索、アルバム詳細、再生状態の進行と一時停止も確認した。音声の継続性は上述のPCM検査で判定している。
- 実機の通常参照版はversionName 9.1.80.2221、更新時刻08:43:48のまま。検証用スロットを削除して元の音質設定へ戻し、検証で開始した再生を停止した。生成したテスト用パッケージだけを取り除き、公開版の音質設定を表示して終了した。最終画像は`build/qa/eq-images/published-*.png`と`delivered-settings.png`、配布物の控えは`build/outputs/play-0.2.0.apk`。

### 先読みの入口に残った同期処理の追加修正（20:33の配布）

- 実機APKのハッシュを照合し、20:02の修正版が入っていることを確認してから再調査した。詳細先読み本体以外に、他タブの先読み開始時の`peekLibrary`と、取得済み詳細を開く際の`peekDetail`が画面スレッドで認証ストアを読んでいた。表示済み一覧はViewModelの状態を使い、その他のキャッシュ確認は既存のRepositoryのIO経路へ移した。キャッシュ再利用時に通信を増やさず、表示済み一覧の重複公開も省いた。
- `LibraryBrowsingTest`の新規2件は修正前に失敗した。他タブの先読み開始後の画面スレッド読み取りは2件、取得済み詳細の表示は1件だった。修正後は両方0件で成功し、詳細の追加通信も0件だった（`build/qa/scroll-burst-main-before.log`、`scroll-burst-avd.log`）。
- 実機の計測を高速フリック220ms、停止900ms、往復12回へ広げ、3タブとプレイリスト内の収録曲を確認した。今回の条件では追加修正前にも50ms超の描画は再現していないため、以下を新たな大幅高速化の根拠とはしない。修正後の4画面すべてで95％地点16.67ms以下の検査に成功した。

| 画面 | 追加修正前の95％地点 / 最大 | 追加修正後の95％地点 / 最大 | 50ms超（前 / 後） |
| --- | ---: | ---: | ---: |
| プレイリスト一覧 | 9.26 / 20.20ms | 9.09 / 14.09ms | 0 / 0 |
| アルバム一覧 | 9.81 / 17.77ms | 9.94 / 20.35ms | 0 / 0 |
| 楽曲一覧 | 8.90 / 21.81ms | 8.93 / 13.69ms | 0 / 0 |
| プレイリスト内 | 7.80 / 25.70ms | 7.80 / 23.92ms | 0 / 0 |

- 生データは`build/qa/scroll-metrics/`の`burst-before.json`、`albums-before.json`、`tracks-before.json`、`detail-before.json`と、対応する4つの`*-burst-after.json`。実機は前項と同じ端末・表示設定で、各測定前にプロセスを再起動した。画像・ディスクキャッシュは維持している。入力前の準備や全端末条件を含む性能保証ではない。
- 追加した画面移動の計測器では、最初の試行で画面識別に失敗した。Composeの仮想アクセシビリティ階層を走査する方式へ直して再実行し、失敗した試行は性能値に使用していない。計測器の追加引数と取得フラグの復元は`how-to-update.md`に記録した。
- 診断WIPを除いた構成でJVM149件、lintエラー0・既存警告5件。分離AVDの一覧/キャッシュ/詳細/編集/認証競合/低速ドラッグ/画面34件が73.296秒で成功し、詳細画面の画像も確認した。実機の検索・詳細・再生画面・保存キャッシュ2件は26.679秒で成功した。ログは`scroll-burst-fix-build.log`、`scroll-burst-avd.log`、`scroll-burst-live-regression.log`。画像は`build/qa/scroll-burst-images/`。
- 依存477件を公開DBとローカル照合し、該当する既知脆弱性0件（`scroll-burst-osv.json`、DB更新は同日03:22:30）。READMEと操作導線も確認し、キャッシュの利用方法は維持している。
- 実機は2026-09-13 20:33:13に追加修正版へ更新した。配布APK`build/outputs/play-debug.apk`と実機のSHA256は`a6ae7741532112229481b43426479786db538c0d3c4e1b86fd481d34df9692c2`で一致した。通常参照版の更新時刻は08:43:48のまま。分離AVDを終了し、実機のPlayを通常起動へ戻した。

### 先読み中のスクロール改善（20:02の配布）

- 低速ドラッグで可視行が変わらない間にも先読みが始まり、画面スレッドで認証ストアを繰り返し読み、詳細取得ごとに一覧を再構築していた。スクロール停止から350ms後に最大4件・同時1件をIO上で先読みし、完了は共有キャッシュだけへ反映するよう変更した。スクロール再開時は待機分を破棄し、進行中の1件は完了させる。
- 同じログイン済みSH-R80P（Android 16、1260×2730）で、旧APKを保った2回と修正版の2回を比較した。各回でプロセスを停止してから起動し、実際の一覧で1.8秒のドラッグを往復6回行った。画像キャッシュは維持し、各版の2回目も再起動して測定した。座標はいずれもx=630、y=721〜2186。以下は描画されたフレームの処理時間で、初回描画は除く。

| 測定 | フレーム数 | 中央値 | 95％地点 | 99％地点 | 最大 | 50ms超 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 修正前1回目 | 392 | 7.54ms | 42.22ms | 674.20ms | 885.81ms | 18 |
| 修正前2回目 | 453 | 6.74ms | 47.37ms | 675.76ms | 804.41ms | 23 |
| 修正後1回目 | 1299 | 4.99ms | 7.74ms | 9.99ms | 14.84ms | 0 |
| 修正後2回目 | 1302 | 4.79ms | 6.52ms | 9.03ms | 11.42ms | 0 |

- OSのフレーム期限超過も修正前35/45件から修正後9/5件へ減った。修正後2回目は95％地点16.67ms以下の明示的な検査にも成功した。端末・一覧・操作条件を固定した比較であり、全端末・通信条件の上限保証ではない。生データは`build/qa/scroll-metrics/`の4 JSON、実行ログは`scroll-before-realclock.log`、`scroll-before-repeat.log`、`scroll-after.log`、`scroll-after-repeat.log`。
- 初期の測定器は`ComposeTestRule`の時計が外部ジェスチャー待機中に止まり、0フレームで失敗した。性能値として採用せず、実時間の`ActivityScenario`と`Window.FrameMetrics`へ変更して前後を測り直した。計測器は通常APKの動作を変更せず、記録には時間・件数・座標のみを含む。
- JVM149件とlintが成功（エラー0、既存警告5件）。診断WIPを含めないステージ済みソースの構成からビルドした（`build/qa/scroll-clean-build.log`）。依存477件を公開DBとローカル照合し、該当する既知脆弱性0件（`scroll-osv.json`）。DB更新日時は同日03:22:30。
- 分離AVDの低速ドラッグ・一覧・詳細・起動キャッシュの26件が63.184秒で成功。低い英語横画面800×360の11件は20.433秒、アラビア語960×1800の11件は25.408秒で成功した。RTLの検索・並び替え画像取得2件も成功し、画像を確認した（`scroll-avd.log`、`scroll-avd-en-low.log`、`scroll-avd-ar.log`、`scroll-avd-ar-images.log`）。
- 実機の画面連携と保存一覧の2件が24.929秒で成功した（`scroll-live-regression.log`）。3タブの絞り込みと保持、検索、作品詳細、再生・拡大プレイヤー、通信できない状態でのディスク一覧表示を確認した。先読み中の画面スレッド上の認証読み取りが0件で、一覧が書き換わらないことは`LibraryBrowsingTest`で検査した。
- 実機は2026-09-13 20:02:18に更新済み。`build/outputs/play-debug.apk`と実機APKのSHA256は`af5a83aa750fb9c2cce6ab10b1e2215a570d35538b09d219b92b0fc0b12c1096`で一致した。プロセスを再起動した通常画面でも、保存ログインとプレイリストの先頭表示を確認した。画像は`build/qa/scroll-images/`。今回の音声・プレイリスト書き込み経路は変更しておらず、全曲音声検査と書き込み検査の結果は下記の既存記録を維持する。

### プレイリスト一覧のディスク保存（19:14の配布）

- 起動時にSQLiteの完全な保存一覧を先に公開し、その後に原本一覧を照合するよう変更した。URI単位で追加・削除・変更したmetadata行と順序を反映し、未変更行は再挿入しない。作成者の表示名と実照会時刻も保持する。
- 分離AVDのSQLite保存・破損復旧・世代照合12件、起動/同期失敗/追加削除/空応答拒否/作成者期限5件、画面・一覧・編集・認証19件の計36件が65.851秒で成功した（`playlist-disk-final-avd.log`）。SQL triggerで未変更行を更新しないことと、途中のSQL失敗で部分反映しないことを検査した。
- 英語800×360の画面10件は16.610秒、アラビア語960×1800の画面10件は21.097秒で成功した。同期失敗の通知から、別タブを表示したままでもプレイリストの同期を再試行できる。`playlist-disk-en-low.log`、`playlist-disk-ar.log`。
- 診断WIPを除いた配布構成でJVM145件成功、lintはエラー0・既存警告5件。依存477件の公開DBによるローカル照合は該当0件。`playlist-disk-delivery-build.log`、`playlist-disk-osv.json`。
- 実機3件が49.001秒で成功した（`playlist-disk-device.log`）。実際の保存一覧を別のストア/コンテナから復元し、同期APIを遮断しても一覧が先に表示され、読み込み表示やモーダルで塞がれないことを確認した。端末の通信設定は変更していない。専用の非公開プレイリストを作成・編集・削除し、次の同期前にそれぞれディスクへ反映され、他の保存項目が維持されることも確認した。
- 続いてPlayのプロセスを停止して通常起動し、保存ログインと一覧表示をネイティブ画面でも確認した。表示画像は`build/qa/playlist-disk-images/`。一時UI取得ファイルを削除し、AVDの表示設定と言語を復元して終了した。
- 配布物は`build/outputs/play-debug.apk`、SHA256は`38f3de527f076bbabb1176fb39732c396cdaff29f12721cf1b688af02fd2b475`。2026-09-13 19:14:00更新後の実機APKと一致する。通常版のストア更新時刻08:43:48は維持している。

### 全タブのメタデータ検索・作成者の表示名（18:15の配布）

- アルバム・楽曲もプレイリストと同じ検索欄と並び替えアイコンを使う。3タブで検索語・並び順を独立保持し、発売日・曲の長さも絞り込み対象とした。条件変更時だけ先頭を表示し、同じ条件で画面へ戻るスクロール位置は維持する。
- 作成者のプロフィールAPIを公開参照コードと照合して接続し、アカウントIDと表示名を別に保持した。実機で自分と他の作成者のプロフィール名を一覧・詳細と照合して成功した。同じ作成者の読み取り共有と手動更新による名前の更新もJVMで確認した。
- 配布構成のJVM145件が成功し、lintはエラー0・既存警告5件。依存477件のローカル脆弱性照合は該当0件。記録は`profile-filter-delivery-build.log`、`profile-filter-osv.json`。
- 日本語の画面・状態・編集19件が57.313秒で成功（`profile-filter-ui-ja.log`）。英語800×360の画面8件が15.966秒（`profile-filter-low-final.log`）、アラビア語960×1800の画面8件が21.714秒（`profile-filter-ui-ar.log`）で成功した。並び替え後に先頭タイトルが切れないことも検査した。
- 最終APKの実機4件が50.552秒で成功（`profile-filter-device-final.log`）。作成者名、4検索語、専用の非公開テストプレイリストの作成・メタデータ・画像・曲・削除、実画面のアルバム/楽曲の絞り込み・並び替え・条件保持・詳細・拡大プレイヤーを確認した。通常の実機キーボードで楽曲のアルバム名を入力し、検索欄・並び替え・一致する曲が表示されることも画像で確認した。
- `build/outputs/play-debug.apk`のSHA256は`e746a01957230b001dbfea706a937b9968669609448fc8c5edc6d7ba83b4091c`。実機の2026-09-13 18:15:44更新APKと一致する。画面はGit管理外の`build/qa/profile-filter-images/`へ保存した。

### ライブラリ・検索・拡大再生画面の更新後（17:31の配布）

- 3種類の一覧保持、可視範囲の詳細先読み、メタデータ表示と絞り込み、並び替え、入力前・入力中の候補、事前検索、拡大プレイヤーを接続した。外部アプリへ開く操作とログイン中のログインメニューを除いた。
- コミット対象だけから生成したAPKでJVM139件が成功。lintはエラー0、既存の警告5件。公開DBとのローカル照合で依存477件に該当する既知脆弱性は0件だった。記録は`build/qa/library-delivery-build.log`、`library-osv.json`。DBの更新時刻は同日03:22。
- 日本語の画面・確認コード・プレイリスト編集26件が成功（`library-ui-ja-final.log`、64.361秒）。英語800×360・160dpiの画面17件（`library-ui-en-compact.log`、24.043秒）とアラビア語960×1800・480dpiの画面17件（`library-ui-ar.log`、31.350秒）も成功した。
- 横画面で検索欄・並び替えが一覧を押しつぶした問題は、低い画面で上部バーへまとめて解消した。遅延項目は実キーで表示してから検査し、タイトルと操作行の完全可視性を維持した。ミニ画面のタイトル部分からの実タップ、拡大画面の戻る、各操作、ドラッグ中の進捗更新、曲変更時の古いシーク破棄も確認した。
- 最終APKの実機13件が89.825秒で成功（`library-delivery-device.log`）。保存ログインと更新、一覧・詳細、英語/日本語/1文字の4検索語、16〜30秒の有音、専用の非公開プレイリストの作成・名前/説明/画像/収録曲変更・削除、実画面から検索・詳細・拡大プレイヤーへ進む操作を含む。音声は数値だけを`library-delivery-amplitudes.log`へ記録した。
- 実機のキーボードを実際に開いて入力し、キーボードの上に入力欄・ライブラリ候補・検索結果が表示されることを画像でも確認した。説明中のHTML表記を読みやすいテキストに整え、検索欄の案内を1行に収めた。画像はGit管理外の`build/qa/library-ui-images/`にある。
- 配布APKは`build/outputs/play-debug.apk`、SHA256は`43a853d12e086f4cd16011ee120d9679cee6127a5c65382791814f6bc4849461`。実機の2026-09-13 17:31:05更新後のAPKと一致した。通常版のストア更新時刻08:43:48は維持している。
- プロセスを停止してから保存ログインと一覧を再確認し、2件が1.428秒で成功した（`library-delivery-restart.log`）。専用音声検査キャッシュと一時UI取得ファイルを削除し、Playを通常起動した。端末のproxyとadb reverseに検証用設定は残っていない。

### 無音化の修正後（同日14:52の配布）

- 最終レビュー後のAPKで、保存ログイン・元認証/再生用認証の更新・再生操作・音声・プレイリスト一巡の実機13件がすべて成功した（`build/qa/final-device-acceptance.log`、90.464秒）。JVM106件とlint（エラー0）も成功した。
- 既存の診断WIPを除き、コミット対象だけから生成した最終APKでも同じ実機13件が94.526秒で成功した（`build/qa/clean-apk-device-acceptance.log`）。分離AVDの画像検査3件と、実機のプロセス再起動後の保存ログイン・一覧取得も成功した。検証用ブラウザープロファイル、所有する端末転送、専用の音声検査キャッシュを片付けた。
- 最終成果物は`build/outputs/play-debug.apk`、SHA256は`bb2bc601616c0cecc95f77fb4f60b68efb9c98a7b8e25144387630c394edafa4`。2026-09-13 14:52:57の実機更新後、インストールされたAPKと一致した。通常版はバージョン`9.1.80.2221`とストア更新時刻08:43:48を維持している。

- Play自身の保存済みログインから、再生に必要な認証と対応する端末認証を内部で取得するように変更した。新しいSDKアプリ登録、参照アプリからの資格情報の持ち込み、外部プレイヤーの起動は行わない。音声は従来どおり標準Media3と端末のDRMで再生し、鍵を抽出・保存しない。
- 通常の再生サービスへ接続した`AudioOutputTest`が2曲で成功した。以前は無音だった16〜30秒を含む音声出力を確認した（`native-playback-audio-output.log`、`native-playback-second-track-audio.log`）。
- `fullTrack=true`も305.251秒で成功した。32秒時点でホーム画面へ移り、約5分の曲が自然に終わるまで背景再生した。検査区間は冒頭15/15秒、中盤19/19秒、終盤20/20秒で有音だった（`native-playback-full-audio.log`、`native-playback-full-amplitudes.log`）。終わりのフェードも記録された。保存するのは音量の数値だけで、音声データは記録しない。
- `LivePlaybackTest`が21.099秒で成功し、一時停止・再開・シーク・前後移動・1曲/全曲リピート・ランダム再生を確認した（`native-playback-controls.log`）。この操作テストのミュート判定を、上記の実音声検査の代わりにはしていない。
- 既存の暗号化MP4とその初期化データをそのまま使用して成功した。新sidecarや鍵識別子の書き換えは製品へ追加していない。再生用認証の期限とアカウント変更を検査し、認証転送・ライセンスの401はそれぞれ1回だけ再取得する。
- HTTPライブラリは既存依存と同じ4.12.0を直接依存として明記した。検証メタデータの追加2件で既存ハッシュの変更はなく、Windows/Linux/macOS用ビルドツールも照合した。OSVの公開DBによるローカル検査は477パッケージ、検出結果0件だった。

画像の旧API向けEXIF処理は、既存依存と同じ[AndroidX ExifInterface 1.4.2](https://developer.android.com/jetpack/androidx/releases/exifinterface)を直接利用するようにした。HTTPライブラリも実行時の版を変えずに直接依存へ明記しており、今回の修正でメジャー更新は行っていない。lintの依存更新案内はこれと既存のCompose BOMに関するもの。保存の書き込み完了を確認するSharedPreferences操作は、戻り値を失わないため直接`commit()`を使う。

### プレイリストの作成・編集・削除

- Play自身の保存済みログインで、検証用の非公開プレイリストの作成、名前・日本語を含む説明の保存、画像の登録と表示、収録曲の追加・取得・削除、説明と画像のクリア、ライブラリからの削除が成功した。画像はURLだけでなく、実際に取得してデコードし、アップロードした画像の画素と照合した。
- `PlaylistAccountTest`に`livePlaylists=true`を明示した実機検証1件が成功した（`build/qa/playlist-account-fourth.log`、15.536秒）。開始前後で既存プレイリストのURI集合が一致することを確認した。途中の失敗時も今回作ったリストだけを削除した。
- 作成のprotobuf型の誤りと、サイズ別画像URLがない応答の読み取り漏れを実機検証で検出して修正した。通信層のJVM検証13件が成功した。
- 最終の画面検証は日本語の縦画面10件、英語800×360のIME表示7件、アラビア語狭幅のIME表示7件が成功した。作成・編集・削除確認・失敗時の入力保持を確認し、実ディスプレイ画像でも保存ボタンがキーボードの上に完全に表示されることを確認した。記録は`build/qa/playlist-ui/`。

再検証には、通常の読み取り検査と分けて`PlaylistAccountTest`を使用する。前回の検証用URIが原本rootlistに残っている場合は新規作成せず停止する。対象リストの削除を確認してから再実行し、既存のリストを清掃対象へ広げない。

### 修正前の無音化を再現・従来の判定を訂正

- ユーザー報告時の実機APKは07:28:50に更新され、手元の最新版とSHA256が一致した。更新漏れではない。
- ミュートなしで既知の実曲を再生し、冒頭には音声があり、暗号化部分が始まる約10秒以降には復号後PCMのRMSがほぼ0になることを確認した。AndroidのAACデコーダーが読み取れないフレームを無音へ置き換えていた。
- `AudioOutputTest` は16〜30秒の15区間に音声が含まれるかを検査し、この不具合で失敗する。`LivePlaybackTest`の位置・終端・操作の検証とは分ける。音声、認証値、鍵は保存しない。
- 標準MediaExtractorとMedia3で、最初の暗号化フレームの時刻・暗号方式・IV・キーID・ペイロードが一致した。標準MediaDrmとMediaCodecの直接接続、および同一端末のブラウザー経路でも対象音源が無音になった。
- 同じ実機の標準DRM・デコーダーで、[公開検証音源](https://github.com/shaka-project/shaka-player/blob/main/demo/common/assets.js)は正常なPCMを出力した（442,368サンプル、RMS約0.0496）。ユーザーによると、Google Playからインストールした通常版では同じ曲が10秒以降も正常に鳴る。
- 対象音源のライセンスは再生可能と判定されるが、正しく復号された音声を取得できていない。サービス側全体の障害とは断定しない。同期デコーダー、L3、別の配信形式、SDKのDRM初期化情報・証明書、接続先・要求ヘッダーを比較したが、修正方法は未特定。比較用の製品設定変更は元へ戻した。
- 調査ログは`build/qa/audio-output-before.log`、`platform-codec-check.log`、`sidecar-codec-check.log`、`certificate-codec-check.log`、`legacy-audio-check.log`、`license-profile-check.log`、`license-global-check.log`、`public-drm-check.log`、`browser-audio-permission-check.log`。後者の位置進行も音声正常を意味しない。
- 整理後の最終APKでも、音声検査は15区間中0区間で失敗した（`build/qa/audio-output-final-regression.log`、`audio-output-final-amplitudes.log`）。保存認証・ライブラリの実機9件は成功し、JVM61件、通常とコミット対象だけのビルド・lintも成功。lintの残る指摘は既存の依存更新案内、書き込み結果の確認に必要なSharedPreferences直接操作、および作業ツリーに残す診断受信機の宣言。

以下の従来記録にある実音源の「完走」は、音量0で再生位置が終端へ到達したという意味に限定する。修正後の音声検証結果はこの文書の冒頭に分けて記録している。

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
- 実曲のライセンス要求の受理と再生位置の終端到達を確認。長さ301,920msの音源で90秒へのシーク、一時停止・再開も確認した。音量0で行ったこの検証は、音声が正常に続くことを確認していなかった。`build/qa/normal-login-live-playback.log`。
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

Pythonの記録ツール検証にはmitmproxyが必要。Kotlinは安定修正版2.4.20へ更新し、既存アーティファクトのハッシュが変わっていないことと、実際のビルド依存が修正版へ解決されることを確認した。最新のlint結果は同日の検証記録を参照する。以前の未コミット作業ツリーにあったDebug専用の通信受信機と診断モジュールは、調査資料を保管したうえで現行ビルドから外している。

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

## Dependabot 自動処理（2026-09-23）

`.github/workflows/dependabot-automation.yml` を actionlint で検査し、PR 用 workflow 名（CI）と一致することを確認する。Dependabot の patch／minor かつ全 PR チェック成功の場合だけ取り込み、major・古い SHA・再失敗は残す。

実際の Dependabot PR がまだない場合、動作経路は未検証として扱う。実 PR 発生後に自動化ジョブ、CI の再試行、マージ結果を確認する。

大量の Dependabot PR により CI 完了より分類が遅れる場合でも、分類後の `workflow_dispatch` が現在の PR 番号と head SHA を照合して再評価する。別の作成者、古い SHA、未完了の CI はマージしない。
