# 第三者の著作物

## Shannon暗号の実装

`app/src/main/java/io/github/playmusic/data/auth/crypto/Shannon.java`は、[librespot-java](https://github.com/librespot-org/librespot-java)の実装を使用しています。

- Copyright 2021 devgianlu
- 原実装の著者: Felix Bruns
- ライセンス: Apache License 2.0
- 変更: Playのパッケージ名へ変更。暗号処理は原実装を維持。

ライセンス全文は`app/src/main/assets/licenses/Apache-2.0.txt`にあり、アプリにも同梱されます。

AP認証の通信契約と鍵交換の実装にあたっては、MITライセンスの[librespot](https://github.com/librespot-org/librespot)の公開仕様と実装を参照しています。
