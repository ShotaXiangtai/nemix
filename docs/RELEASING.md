# リリース手順 / Releasing

## Android

### 1. 署名鍵を作る（最初の1回だけ）

署名鍵は自分で作る自己署名の鍵です。証明書に入れる名前は本名でなくて構いません（例: `CN=nemix`）。

```sh
keytool -genkeypair -v -keystore nemix-release.jks -alias nemix \
  -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=nemix, O=nemix"
```

> **重要**: この `.jks` ファイルとパスワードを失うと、同じアプリとしてアップデートを配布できなくなります。
> リポジトリの外の安全な場所に保存し、別の場所にもバックアップしてください。絶対にコミットしないでください。

### 2. GitHub Secrets に登録する

```sh
base64 -w0 nemix-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R ShotaXiangtai/nemix
gh secret set ANDROID_KEYSTORE_PASSWORD -R ShotaXiangtai/nemix   # 入力を求められます
gh secret set ANDROID_KEY_ALIAS -R ShotaXiangtai/nemix -b nemix
```

### 3. リリースする

1. `bitchat-android/app/build.gradle.kts` の `versionName` と `versionCode` を上げてコミット
2. `versionName` と同じタグを push（例: `versionName = "0.1.0"` → `v0.1.0`。テスト版は `v0.1.0-beta.1`）

```sh
git tag v0.1.0 && git push origin v0.1.0
```

[Android ワークフロー](../.github/workflows/android.yml) が署名済み APK をビルドし、GitHub Release に添付します。
`-` を含むタグはプレリリースになります。サイトのダウンロードボタンは最新の正式リリースの
`nemix-android-universal.apk` を指します。

## iOS（Mac なし）

1. `nemix-ios/` に変更を push すると [iOS ワークフロー](../.github/workflows/ios.yml) が macOS ランナーでビルドします
2. ビルドエラーは Actions のログ（失敗時は `ios-build-logs` アーティファクト）で確認します
3. 成功すると `nemix-ios-unsigned-ipa` アーティファクトができます

### 実機で試す（Windows から）

Windows 版の Sideloadly などのツールで、未署名 IPA を自分の Apple ID で署名し直して iPhone に入れられます。

- 無料の Apple ID: 7日ごとに入れ直しが必要、同時に3アプリまで
- iPhone 側で「設定 > プライバシーとセキュリティ > デベロッパモード」を有効にする必要があります

### TestFlight / App Store

Apple Developer Program（年額）への登録が必要です。登録後、App Store Connect API キーと配布用証明書を
GitHub Secrets に登録すれば、CI から TestFlight にアップロードするジョブを追加できます。

## Website

`website/` を `main` に push すると [Website ワークフロー](../.github/workflows/pages.yml) が GitHub Pages に公開します。
