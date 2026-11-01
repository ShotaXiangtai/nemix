# nemix プライバシーポリシー

最終更新日: 2026年11月1日

nemix（以下「本アプリ」）は、中央サーバーを持たない P2P メッセンジャーです。本ポリシーは、本アプリがどのような情報をどこで扱うかを説明します。

## 1. 開発者が収集する情報

**開発者は、ユーザーの個人情報を一切収集しません。**
本アプリには開発者が運営するサーバー、アカウント登録、広告、アナリティクス、トラッキング、クラッシュレポートの自動送信はありません。

## 2. 端末内にのみ保存される情報

以下の情報はユーザーの端末内にのみ保存され、開発者に送信されることはありません。

- 暗号鍵（ID 鍵・グループ鍵）
- プロフィール（表示名・アイコン画像）
- 連絡先、チャット履歴、グループ情報、設定

チャット履歴やグループ情報は端末内で暗号化して保存されます。アプリを削除するか「緊急データ全消去」を実行すると消去されます。

## 3. 通信相手に送信される情報

- **メッセージ・画像・音声・通話**: エンドツーエンドで暗号化され、宛先の相手だけが復号できます
- **プロフィール**: 設定した表示名とアイコンは、通信相手に暗号化して同期されます
- **画像**: 送信前に EXIF（位置情報・撮影機器情報など）を自動で除去します
- **位置情報**: 送信ボタンを押したときだけ OS 標準機能で取得し、暗号化して相手に送信します。常時追跡は行いません

## 4. インターネット経由の通信（任意）

インターネット経由の通信機能を使う場合、本アプリは第三者が運営する公開 Nostr リレーを使って暗号化されたデータを中継します。

- リレーに送られるメッセージの内容は暗号化されており、リレー運営者は読めません
- ただし、リレー運営者は接続元の **IP アドレス** と通信の時刻・量を知ることができます。IP アドレスを隠したい場合は、アプリ内の Tor 機能を有効にしてください
- ランダム UUID による友達検索を使う場合、UUID から導出した鍵で暗号化された連絡先レコードがリレーに公開されます。UUID を知らない人は内容を読めません
- 利用するリレーは設定で変更できます。各リレーの扱いは、それぞれの運営者のポリシーに従います

## 5. 端末の権限

| 権限 | 目的 |
|---|---|
| Bluetooth / 近くのデバイス | オフラインのメッシュ通信 |
| 位置情報 | Android で Bluetooth スキャンを行うための OS 要件、および位置情報の送信（任意） |
| Wi-Fi / ローカルネットワーク | Wi-Fi Aware による端末間通信、端末間データ移行 |
| マイク | 音声メッセージと音声通話 |
| カメラ | QR コードの読み取り、写真の送信 |
| 通知 | 新着メッセージ・着信の通知 |
| 生体認証 | アプリロック（認証情報は OS が管理し、本アプリは取得しません） |

## 6. 外部リンク

位置情報カードから地図を開いた場合、その座標は地図サービス（OpenStreetMap など）に送信されます。これはユーザーが操作したときのみ発生します。

## 7. 子どもの利用

本アプリは13歳未満の方を対象としていません。

## 8. 本ポリシーの変更

本ポリシーを変更した場合は、本ページと GitHub リポジトリで告知します。

## 9. お問い合わせ

- メール: support@nemi-x.com
- GitHub Issues: https://github.com/ShotaXiangtai/nemix/issues

---

## Summary (English)

- The developer collects **no personal data**. There are no developer-run servers, accounts, ads, analytics or tracking.
- Keys, profile, contacts and chat history are stored **only on your device** (encrypted at rest).
- Messages, media and calls are **end-to-end encrypted**; EXIF metadata is stripped from images.
- Optional internet mode relays encrypted data through **third-party public Nostr relays**, which can see your IP address (use the built-in Tor option to hide it).
- Location is sent only when you explicitly share it.
