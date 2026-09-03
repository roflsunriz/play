# 更新手順

## 前提

- 作業前に `COMMON-AGENTS.md` と `AGENTS.md` を全文確認する。
- `git status --short --branch` で既存差分を確認する。
- Web APIの変更履歴、Android SDK、AGP、Compose BOM、Kotlin、依存ライブラリの公式リリースを確認する。

## 手順

1. `gradle/libs.versions.toml` の固定バージョンを公式リリースに合わせて更新する。
2. Web APIのOpenAPI仕様と移行ガイドを確認し、削除・変更されたエンドポイントやフィールドをデータ層へ反映する。
3. 新しいAPKを `service-apks/` に置き、AAPT2とJADXでSDK、マニフェスト、認証、プレイヤー、キャッシュの差分を確認する。
4. Login5のワイヤ契約が変更されていないか、公開OSS（librespot / librespot-java / spotcontrol）の `login5.proto`・`client_token.proto` と照合する。エンドポイントURL・protobufフィールド番号・client_id・hashcash要件に変更があれば `data/auth/` へ反映する。
5. spclient.wg.spotify.com のWeb APIプロキシ動作を実機で確認し、パス構造や認証方式の変更を `data/api/` へ反映する。
6. `docs/apk-analysis.md` と `CHANGELOG.md` を更新する。
7. 依存関係を変更した場合は、解決済み依存の検証値を更新する。

```powershell
.\gradlew.bat --write-verification-metadata sha256 testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Linux/macOS用AAPT2を更新した場合は、各classifierも一時的な検証用Configurationで解決し、`gradle/verification-metadata.xml` へGradle自身にSHA-256を追記させる。生成物を手編集しない。

8. 次を実行する。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

9. `gradle/verification-metadata.xml` をOSV-Scannerのオフラインデータベースで検査し、例外が必要な場合は `gradle/osv-scanner.toml` に期限、根拠、緩和策を記録する。
10. 実機で `verification.md` のログイン、検索、全再生操作、再起動後ログイン保持、キャッシュ上限、楽曲プレビュー再生を確認する。

## ロールバック

問題がある更新は該当する1コミットをrevertし、旧バージョンの依存関係とAPI処理へ戻す。暗号化セッション形式を変更した場合は、旧形式を誤読せず安全にログアウト状態へ戻せることを確認する。
