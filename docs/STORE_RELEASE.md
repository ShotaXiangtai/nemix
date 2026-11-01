# ストア公開ロードマップ

- **Android**: Google Play 内部テスト → クローズドテスト（ベータ） → 正式公開
- **iOS**: TestFlight（内部 → 外部） → App Store 正式リリース

アップロードは GitHub Actions から行うので Mac は不要です。アカウント登録と審査用の入力はブラウザで行います。

---

## Android / Google Play

### 1. Play Console に登録する

- Google Play Console で**個人**のデベロッパーアカウントを作成します（登録料は1回のみ）。
- 本人確認には本名と身分証が必要です。ストアに出る**デベロッパー名は「nemix」などにできます**。
- ストアには連絡先メールアドレスが公開されます。nemix 専用のアドレスを用意することをおすすめします。

> **重要**: 新しく作った個人アカウントは、正式公開の前に
> **クローズドテストを 12 人以上のテスターで 14 日間連続**で実施する必要があります。
> テスターは早めに集めてください。

### 2. 署名鍵

[RELEASING.md](RELEASING.md) の手順で鍵を作り、GitHub Secrets（`ANDROID_KEYSTORE_*`）に登録します。

Play アプリ署名の設定では、Google に鍵を作らせずに**自分の鍵をアップロード**することをおすすめします
（Play Console の案内にある PEPK ツールで暗号化してアップロードします）。
こうしておくと GitHub Releases 版と Play 版の署名が同じになり、ユーザーがどちらからでも更新できます。

### 3. アプリを作成し、最初の AAB を手動でアップロードする

1. Play Console で新しいアプリを作成します（パッケージ名 `im.nemix.android`）
2. GitHub の Actions → **Google Play** を `upload = false` で実行します
3. 成果物 `nemix-android-aab` をダウンロードし、Play Console の「内部テスト」に手動でアップロードします
   （新しいアプリの最初の1回は API からアップロードできないため）

### 4. CI から自動アップロードできるようにする

1. Google Cloud Console でプロジェクトを作り、**Google Play Android Developer API** を有効にします
2. サービスアカウントを作り、JSON キーをダウンロードします
3. Play Console の「ユーザーと権限」でそのサービスアカウントを招待し、リリース権限を付与します
4. JSON の内容を Secret に登録します

```sh
gh secret set PLAY_SERVICE_ACCOUNT_JSON -R ShotaXiangtai/nemix < service-account.json
```

以降は Actions → **Google Play** から、トラック（`internal` / `alpha` = クローズド / `beta` = オープン / `production`）を選んで実行するだけです。
アップロードのたびに `bitchat-android/app/build.gradle.kts` の `versionCode` を上げてください。

### 5. ストア掲載情報とアプリのコンテンツ

| 項目 | 内容 |
|---|---|
| 掲載文 | `bitchat-android/fastlane/metadata/android/ja-JP/`・`en-US/` にあります |
| アイコン | 512×512（同フォルダの `images/icon.png`） |
| スクリーンショット | スマホ用を最低2枚（実機で撮影してください） |
| フィーチャーグラフィック | 1024×500 |
| プライバシーポリシー | https://nemi-x.com/privacy |
| 連絡先メール | support@nemi-x.com |
| 広告 | なし |
| 対象年齢 | 13歳以上 |
| コンテンツのレーティング | アンケートで「ユーザー同士がやり取りできる」に該当すると回答 |

**データセーフティ（回答案）**: Google Play にアップロードするのは **play 版**（Google ML Kit と Google Play 開発者サービスを含む版）です。
開発者自身はデータを受け取りませんが、データセーフティの回答には**アプリに組み込んだ SDK が収集するデータも含める**必要があります。
Google が公開している ML Kit と Google Play 開発者サービス（位置情報）のデータセーフティ向けの説明を確認し、そこに書かれたデータ（アプリの情報やパフォーマンス、デバイス ID など）を申告してください。
ユーザー間でエンドツーエンド暗号化されたメッセージは、開発者のデータ収集には該当しません。

### 6. 審査で説明が必要な権限

以下の権限は Play Console での申告（用途の説明や動画）が必要です。審査で最も止まりやすいところです。

| 権限 | 対応 |
|---|---|
| `ACCESS_BACKGROUND_LOCATION` | 申告と動画が必要で、却下されやすい権限です。**不要なら削除をおすすめします**（BLE スキャンは Android 12 以降 `BLUETOOTH_SCAN` で足ります） |
| フォアグラウンドサービス（`connectedDevice` / `dataSync` / `location`） | 種類ごとに用途を申告し、動画を提出します |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | 写真・動画の権限ポリシーの対象です。写真選択ツール（Photo Picker）への切り替えを検討してください |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | メッシュの常時受信に必要な理由を説明します |

---

## iOS / TestFlight → App Store

### 1. Apple Developer Program に登録する

- 年会費が必要です。登録は iPhone の「Apple Developer」アプリから行えます。
- **個人で登録すると、App Store の販売者名は本名になります。**「nemix」名義にするには、法人として登録する必要があります（D-U-N-S 番号が必要です）。

### 2. App Store Connect の準備（ブラウザで行います）

1. 「アプリ」→「新規アプリ」で作成します（バンドル ID `im.nemix.ios`）。
   バンドル ID が候補に出ない場合は、Developer サイトの Identifiers で先に登録してください。
2. 「ユーザーとアクセス」→「統合」→「App Store Connect API」で、**Admin 権限**のキーを作成します。
   `.p8` ファイルは1回しかダウンロードできません。Admin 権限は、Mac なしで証明書を自動作成するのに必要です。
3. Secrets を登録します。

```sh
gh secret set ASC_KEY_ID -R ShotaXiangtai/nemix -b <キーID>
gh secret set ASC_ISSUER_ID -R ShotaXiangtai/nemix -b <発行者ID>
gh secret set ASC_KEY_P8 -R ShotaXiangtai/nemix < AuthKey_XXXXXXXXXX.p8
gh secret set APPLE_TEAM_ID -R ShotaXiangtai/nemix -b <チームID>
```

### 3. TestFlight

1. Actions → **iOS TestFlight** を実行すると、ビルドが App Store Connect にアップロードされます（ビルド番号は自動で増えます）
2. **内部テスター**（チームのメンバー、最大100人）には審査なしですぐ配布できます
3. **外部テスター**（最大1万人、公開リンクも可）は、最初のビルドで「ベータ版 App 審査」を受けます

### 4. App Store 審査で必要なもの

| 項目 | 内容 |
|---|---|
| App のプライバシー | 「データの収集なし」 |
| 暗号化（輸出コンプライアンス） | アップロードのたびに質問されます。下の「暗号の輸出規制」を参照 |
| CallKit | **中国本土のストアでは CallKit を使うアプリは配信できません**。配信地域から中国本土を外してください |
| Bluetooth のバックグラウンド動作 | 審査メモに「圏外時のメッシュ中継のため」と説明を書きます |
| スクリーンショット | 6.9 インチの iPhone 用が必須です |
| 審査用の説明 | 2台目の端末が必要な機能なので、確認手順を審査メモに書きます |

---

## 暗号の輸出規制（両ストア共通）

nemix はエンドツーエンド暗号を使っているので、米国の輸出規制（EAR）の対象になります。

- 一般的な方法は、**公開されているオープンソースの暗号**として、ソースコードの URL を米国商務省（BIS）と NSA にメールで一度届け出ることです（EAR §742.15(b)）。
- 届け出たうえで、App Store Connect では「標準の暗号アルゴリズムのみを使用」に沿って回答します。
- 法的な判断になるので、確実を期すなら専門家に確認してください。届け出が済んだら、Info.plist に `ITSAppUsesNonExemptEncryption` を設定すると、毎回の質問を省略できます。

---

## 正式公開の前に終わらせる必要がある開発作業

- [ ] **iOS ⇄ Android 間の通信**（現在の iOS 版は Android と通信できません）。審査で「機能が不完全」と判断されるリスクがあります
- [ ] Android のユニットテストで失敗している 3 件
- [ ] 実機 2 台以上でのオフライン通信テスト（1対1、グループ、画像、位置情報、通話）
- [ ] 不要な権限の削除（上の表を参照）
