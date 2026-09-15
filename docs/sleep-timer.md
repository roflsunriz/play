# スリープタイマー

## 操作

ハンバーガーメニューの「スリープタイマー」から、経過時間の時間・分、または24時間表記の時刻を指定する。経過時間は1分〜99時間59分。過ぎた時刻は翌日の同じ時刻として扱う。設定済みのタイマーは同じ画面で変更・解除できる。

指定時間に再生設定のフェードアウト秒数（1〜12秒）で減衰を開始し、再生停止後に画面を消灯・ロックする。通常停止用のフェード無効設定にかかわらず、タイマーはこの秒数で減衰する。既に再生が終わっている場合は音声を再開せず、消灯・ロックだけを行う。端末を再起動した場合は解除される。

初回は画面内の案内からAndroidの「端末管理者」で画面ロックを許可し、Android 12以降では「アラームとリマインダー」も許可する。要求する端末管理ポリシーは画面ロックだけ。画面ロック後の解除ではPIN・パターン・パスワードが必要になる。タイマー機能を使わなくなったときは、タイマーを解除した後、Androidの端末管理アプリ設定からPlayの管理者権限を解除できる。

権限がない状態では設定ボタンを無効にし、許可画面への導線を表示する。端末管理機能を持たない端末と、自動車向け端末ではこの方式による画面消灯に対応しない。未対応や失敗を「スリープ済み」と扱わない。

## 実装と根拠

2026-09-15にAndroid公式資料で確認した。

- [DevicePolicyManager.lockNow](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#lockNow())：端末管理者の`force-lock`ポリシーで画面をロックする。ロック方式を設定していない端末はスリープのみ。自動車向けでは画面が消えないため、対象から明示的に除く。
- [アラームのスケジュール](https://developer.android.com/develop/background-work/services/alarms)：`setExactAndAllowWhileIdle`と`SCHEDULE_EXACT_ALARM`の実行時確認を使用する。許可を失った状態で不正確なタイマーへ置き換えない。
- [端末管理の概要](https://developer.android.com/work/device-admin)：端末管理者をユーザーが有効化するためのAndroid標準画面を使う。

経過時間は`ELAPSED_REALTIME_WAKEUP`で端末の時計変更から独立させ、時刻指定は`RTC_WAKEUP`を使用する。`noBackupFilesDir`の`AtomicFile`に予約ID・期限・起動回数を保存する。世代IDを照合して旧予約を受け付けず、Androidがアラームを削除する端末再起動後は予約表示も解除する。状態保存とアラーム登録はUIの回転によるキャンセルで途中までにならないよう完了させる。

アラームは非公開のBroadcastReceiverへ配送し、最大12秒のフェードと完了処理の間だけ最大25秒のpartial wake lockを取る。foregroundフラグを付けないbroadcastの`goAsync`の枠内で完了させる。再生サービスが既に終わっているときに新しくサービスを起動したり無音再生したりしない。2つのデコーダーの実状態を確認し、通常の一時停止フェード中にも音声が残っていれば対象にする。タイマーの減衰後は直ちに両エンジンを止め、通常停止のフェードを重ねない。システム全体の音量や画面タイムアウト設定は変更しない。

## 検証

- JVM：`SleepTimerTest`。時間・分の境界、翌日・月末・夏時間、フェード中の音量、停止とロックの順序、キャンセル時の音量復元、終端済みと途中終端を確認する。
- Compose：`SleepTimerDialogTest`。端末の実際の権限を変更せず、権限なしの説明・許可ボタン、入力境界、時刻切替、設定・解除・閉じる操作を検査する。
- AVD限定：`SleepTimerIntegrationTest`。`sleepTimer=true`の明示指定と`ranchu`/`goldfish`の両方を必須とする。実機で実行しない。専用AVDの画面ロックと正確なアラームを許可してから実行する。

```powershell
adb -s <AVD_SERIAL> shell dpm set-active-admin io.github.playmusic/.data.playback.SleepTimerAdminReceiver
adb -s <AVD_SERIAL> shell appops set io.github.playmusic SCHEDULE_EXACT_ALARM allow
adb -s <AVD_SERIAL> shell am instrument -w -r -e sleepTimer true -e class io.github.playmusic.SleepTimerIntegrationTest io.github.playmusic.test/androidx.test.runner.AndroidJUnitRunner
```

統合テストは合成無音音源と実際のMedia3サービスを使用し、1分のOSアラームを待つ。音量の中間値、停止、音量復元、画面の非interactive状態を確認する。別ケースでは先に曲を自然終端させ、アラーム後も終端状態・位置を保持して消灯することを確認する。音声出力そのもののフェード聴取はこの無音音源では証明しない。専用AVDでの結果と、必要な実音源の確認結果は`verification.md`へ記録する。
