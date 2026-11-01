package com.bitchat.android.legal

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Terms of Service & Privacy Policy acceptance state and canonical text for nemix.
 */
object TermsOfServiceManager {
    private const val PREFS_NAME = "nemix_legal_prefs"
    private const val KEY_ACCEPTED_VERSION = "accepted_tos_version"
    const val CURRENT_TOS_VERSION = 1

    const val SHORT_DECLARATION =
        "本アプリは、災害時にも機能する「オフライン通信の確保」、帯域を節約する「トラフィック負荷の軽減」、特定企業に縛られない「プラットフォーム依存の脱却」を同時に達成するサーバーレスSNSです。"

    data class CoreChallenge(
        val title: String,
        val description: String
    )

    val THREE_CORE_CHALLENGES = listOf(
        CoreChallenge(
            title = "1. オフライン通信の確保",
            description = "インターネットインフラが遮断された災害時や航空機内での連絡手段を提供します。"
        ),
        CoreChallenge(
            title = "2. プラットフォーム非依存の実現",
            description = "中央サーバーを廃し、一企業の規約変更やサービスダウンに左右されない自由なインフラを作ります。"
        ),
        CoreChallenge(
            title = "3. トラフィック負荷の分散",
            description = "エンドツーエンドの直接通信により、グローバルな通信帯域やサーバーリソースの消費を抑えます。"
        )
    )

    private var prefs: SharedPreferences? = null
    private val _acceptedFlow = MutableStateFlow(false)
    val acceptedFlow: StateFlow<Boolean> = _acceptedFlow.asStateFlow()

    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _acceptedFlow.value = sp.getInt(KEY_ACCEPTED_VERSION, 0) >= CURRENT_TOS_VERSION
    }

    fun hasAccepted(context: Context): Boolean {
        if (prefs == null) init(context)
        return _acceptedFlow.value
    }

    fun acceptTerms(context: Context) {
        if (prefs == null) init(context)
        prefs?.edit()?.putInt(KEY_ACCEPTED_VERSION, CURRENT_TOS_VERSION)?.apply()
        _acceptedFlow.value = true
    }

    data class LegalArticle(
        val title: String,
        val body: String
    )

    val ARTICLES = listOf(
        LegalArticle(
            "第1条（本アプリの目的と正当利用の原則）",
            "1. 本アプリ「nemix」は、大規模災害・通信障害時における「オフライン通信の確保」、特定事業者のシステム障害や規約変更に依存しない「プラットフォーム非依存の実現」、および端末間のエンドツーエンド直接通信による「トラフィック負荷の分散」という課題を解決することのみを目的として提供されるソフトウェアです。\n" +
                "2. 開発者は、本アプリが犯罪行為、著作権侵害、違法情報の流通、または第三者の権利侵害に利用されることを一切意図しておらず、これらを断固として許容しません。"
        ),
        LegalArticle(
            "第2条（サーバーレス構造および非保管性）",
            "1. 本アプリは中央集権的なメッセージ保管サーバーを持たないピアツーピア（P2P）無線メッシュ通信および分散型リレープロトコルを用いた非保管型（ノンカストディアル）ソフトウェアです。\n" +
                "2. 秘密鍵・暗号鍵・履歴はユーザーの端末内にのみ保存されます。開発者は通信内容や暗号鍵を保有・閲覧・復号・検閲・復元する技術的手段を有しません。\n" +
                "3. ネット経由通信（リレーサーバーやTor等）を利用する場合、第三者運営ネットワークの可用性や安全性について開発者は保証しません。"
        ),
        LegalArticle(
            "第3条（メッシュ中継・ネット中継ブリッジ機能）",
            "1. 近隣端末間で暗号化パケットを中継するローカルメッシュ機能、および任意で有効化できるメッシュ・インターネット中継ブリッジ機能では、すべての個別通信がエンドツーエンド暗号化（E2EE）されています。\n" +
                "2. 中継端末のユーザーが通信内容を閲覧・復号することはできません。ユーザーは設定に応じたゼロ知識（内容非知得）の自動パケット中継動作に同意するものとします。通信料は各ユーザーの負担となります。"
        ),
        LegalArticle(
            "第4条（電波法・航空法・各国通信規制の遵守）",
            "1. 【航空機内での利用】航空機内で利用する場合、必ず航空法、各航空会社の機内規則、および乗務員の指示を厳守してください。離着陸時や電波使用禁止状況では直ちに通信を停止し、機内モード設定の上でBluetooth/Wi-Fiの使用が許可されている機材・時間帯に限り利用してください。\n" +
                "2. 【電波法の遵守】日本国の電波法・電気通信事業法および滞在国の電波・暗号規制を自己の責任で遵守し、病院の指定区域や医療機器付近では使用を控えてください。"
        ),
        LegalArticle(
            "第5条（災害・緊急時の無保証および公的通報の非代替性）",
            "1. 無線通信の性質上、距離・遮蔽物・電池残量・中継端末の有無等により通信が遅延・不達となる場合があります。\n" +
                "2. 本アプリは警察（110番）・消防救急（119番）・海上保安庁（118番）等の公的緊急通報手段を代替するものではありません。生命・身体に関わる緊急時は必ず公的通報手段を最優先してください。"
        ),
        LegalArticle(
            "第6条（禁止事項・Google Play UGC規約準拠）",
            "以下の行為を固く禁じます。\n" +
                "・法令または公序良俗に違反する行為、犯罪予告・関与・教唆\n" +
                "・児童の性的虐待と搾取（CSAE / CSAM）に関連する一切のコンテンツの作成・送信・共有（ゼロトレランス方針）\n" +
                "・著作権・商標権・肖像権・プライバシー権・名誉権を侵害する画像・音声・データの送信\n" +
                "・脅迫、誹謗中傷、ハラスメント、差別、自傷勧誘、暴力的・露骨なコンテンツの送信\n" +
                "・相手の同意なき位置情報監視・ストーカー行為\n" +
                "・マルウェア送信、大量連投（スパム）等による通信妨害・なりすまし行為"
        ),
        LegalArticle(
            "第7条（安全対策・ブロック・迷惑行為の遮断）",
            "不適切な相手や通信に対しては、アプリ内の「ユーザーブロック機能」「スパムフィルター」「登録した人のみ近くの人通知」「緊急データ全消去」により端末側で即座に遮断できます。違法行為に遭遇した際は直ちにブロックし、関係当局へ通報してください。"
        ),
        LegalArticle(
            "第8条（プライバシーポリシー・データ最小化）",
            "・開発者による収集なし：開発者がサーバー上で個人情報・位置情報・チャット・通話・画像を収集・保存・広告利用することは一切ありません。\n" +
                "・プロフィール＆画像：設定した名前と軽量圧縮アイコンは通信相手へ暗号化同期されます。アイコンや送信画像からはEXIF位置・撮影情報を自動除去します。\n" +
                "・位置情報共有：送信ボタンを押した時のみOS標準機能で座標を取得して暗号化送信し、外部マップSDKによる常時追跡は行いません。\n" +
                "・無線音声通話：リアルタイム暗号化伝送され、外部サーバーに録音されません。"
        ),
        LegalArticle(
            "第9条（端末データ移行・自己管理責任）",
            "暗号鍵やチャット履歴、端末引き継ぎ・バックアップのパスワード管理はユーザー自身の責任で行ってください。開発者はデータを保持しないため、端末紛失やパスワード忘却時の復旧はできません。"
        ),
        LegalArticle(
            "第10条（公式配信・免責・準拠法）",
            "1. 本アプリは公式GitHubリポジトリのReleasesおよび公式アプリストアで配信します。アプリ内での自動バイナリ更新は行いません。公式以外から入手したAPKは改ざんの恐れがあるため、署名を確認のうえご利用ください。\n" +
                "2. 本アプリは現状有姿（AS-IS）で無償提供され、消費者契約法等の強行法規に反しない限り、通信不達・データ消失・ユーザー間紛争等の損害について開発者は責任を負いません。\n" +
                "3. 本規約は日本法に準拠します。"
        ),
        LegalArticle(
            "第11条（オープンソースライセンス）",
            "本アプリはGNU General Public License v3.0（GPL-3.0）のもとで公開されているオープンソースソフトウェアです（bitchat-android の派生物）。本規約のいかなる条項も、GPL-3.0がユーザーに認める複製・改変・再配布等の権利を制限するものではありません。ソースコードは公式GitHubリポジトリで入手できます。"
        )
    )
}

