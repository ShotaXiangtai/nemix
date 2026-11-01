# F-Droid での公開

nemix の Android 版には2つの版（プロダクトフレーバー）があります。

| フレーバー | 用途 | Google のライブラリ |
|---|---|---|
| `fdroid` | F-Droid、GitHub Releases（プライバシー版） | なし（ZXing、Android LocationManager） |
| `play` | Google Play | Google ML Kit、Google Play 開発者サービス（位置情報） |

`fdroid` 版のランタイム依存に Google の非公開ライブラリが含まれないことは、`app/gradle.lockfile`（`fdroid*RuntimeClasspath`）と、ビルドした APK の dex で確認済みです。

## 公開ルート

### 1. IzzyOnDroid（早い・おすすめの第一歩）

IzzyOnDroid は、GitHub Releases の APK をそのまま配信する F-Droid 互換のリポジトリです。F-Droid アプリに標準で入っています。

- 申請: https://gitlab.com/IzzyOnDroid/repo/-/issues で「New app」の Issue を作成（GitLab アカウントが必要）
- 伝える内容: リポジトリ URL、APK 名 `nemix-android-universal.apk`、ライセンス GPL-3.0
- 署名は GitHub Releases と同じ nemix の鍵（SHA-256 `C8:75:28:0E:3B:85:7E:05:E8:0B:57:5E:B6:9A:C2:6D:A0:EB:85:47:FE:2E:5B:C6:80:02:18:E2:E1:D0:DE:B6`）

### 2. F-Droid 本体（時間はかかるが最も信頼される）

F-Droid のビルドサーバーがソースからビルドします。申請は fdroiddata への Merge Request で行います。

- https://gitlab.com/fdroid/fdroiddata をフォークし、`metadata/im.nemix.android.yml` を追加して Merge Request を作成（GitLab アカウントが必要）
- 再現可能ビルドにすると、F-Droid も nemix の鍵で署名された APK を配信できます（GitHub 版から乗り換えても更新できる）

#### メタデータの下書き

```yaml
Categories:
  - Internet
License: GPL-3.0-or-later
AuthorName: nemix
AuthorEmail: support@nemi-x.com
WebSite: https://nemi-x.com
SourceCode: https://github.com/ShotaXiangtai/nemix
IssueTracker: https://github.com/ShotaXiangtai/nemix/issues

AutoName: nemix

RepoType: git
Repo: https://github.com/ShotaXiangtai/nemix.git
Binaries: https://github.com/ShotaXiangtai/nemix/releases/download/v%v/nemix-android-universal.apk

Builds:
  - versionName: 0.1.0
    versionCode: 1
    commit: v0.1.0
    subdir: bitchat-android/app
    sudo:
      - apt-get update
      - apt-get install -y curl build-essential
      - curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain none
    gradle:
      - fdroid
    gradleprops:
      - BITCHAT_GITHUB_RELEASE_CERT_SHA256=c875280e3b857e05e80b575eb69ac26da0eb8547fe2e5bc6800218e2e1d0deb6
    rm:
      - bitchat-android/app/src/main/jniLibs
    prebuild:
      - source $HOME/.cargo/env
      - ../tools/arti-build/build-arti.sh --release
    ndk: r27d

AllowedAPKSigningKeys: c875280e3b857e05e80b575eb69ac26da0eb8547fe2e5bc6800218e2e1d0deb6

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: 0.1.0
CurrentVersionCode: 1
```

#### 申請前に残っている作業

- [ ] **Arti（Tor）をソースからビルドする手順の検証**: 同梱の `.so` は F-Droid では使えないため、上の `prebuild` で `tools/arti-build/build-arti.sh` を使ってビルドします。F-Droid のビルド環境で通るかを、`fdroidserver` を使ってローカル（Linux / Docker）で確認する必要があります
- [ ] **正式版タグ**: F-Droid の `Binaries` と `UpdateCheckMode` は `v0.1.0` のような正式版タグを前提にしています（`-beta` 付きのタグは対象外）
- [ ] **再現可能ビルドの確認**: F-Droid でビルドした APK と、GitHub Releases の APK の中身が一致するか（`apksigcopier` で確認）
- [ ] **アンチフィーチャー**: 公開 Nostr リレーを使う機能について `NonFreeNet` に該当するかを、F-Droid のレビュー担当者と確認する（Nostr は自由なプロトコルなので、該当しない見込み）

## 注意: Google の Android デベロッパー認証

Google は、Google Play 以外（F-Droid や APK の直接配布を含む）で配布されるアプリについても、Google 認定の Android 端末にインストールするには、開発者が Google に本人確認とアプリの登録をすることを求める制度を、国ごとに順次導入しています。日本で始まると、F-Droid 版や GitHub 版でも登録が必要になる可能性があります。導入時期と、趣味の開発者向けの扱いを、そのつど確認してください。
