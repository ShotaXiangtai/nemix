import SwiftUI

// MARK: - iOS Contact Exchange View with Liquid Glass Aesthetics
// Features:
// - Permanent QR / URL with instant reissuance (revoking older links)
// - Expiring QR / URL (10m / 1h / 24h)
// - Real-time CoreImage QR generation with specular refractive frame
// - Deep Link (nemix://contact/add) & Web Share (https://nemix.im/c)
// - 1-tap Copy & iOS Share Sheet

public struct ContactExchangeView: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var appState: AppState

    @State private var activeTab: Int = 0 // 0: My QR/URL, 1: Import
    @State private var selectedType: InviteType = .permanent
    @State private var selectedDurationSeconds: TimeInterval = 3600 // 1 hour
    @State private var showRevokeAlert = false
    @State private var manualInputUrl: String = ""
    @State private var parsedResultText: String?
    @State private var isError: Bool = false
    @State private var showShareSheet = false
    @State private var shareUrlToExport: URL?

    public init() {}

    public var body: some View {
        ZStack {
            LiquidMeshBackground()

            VStack(spacing: 0) {
                // Sheet Top Bar
                HStack {
                    Text("QR・URLで連絡先交換")
                        .font(.system(size: 18, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                    Spacer()
                    Button(action: { dismiss() }) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 20))
                            .foregroundColor(.white.opacity(0.6))
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 18)
                .padding(.bottom, 12)

                // Segmented Tab Picker
                Picker("交換モード", selection: $activeTab) {
                    Text("自分のQR・URL").tag(0)
                    Text("相手を追加").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 20)
                .padding(.bottom, 14)

                ScrollView {
                    VStack(spacing: 16) {
                        if activeTab == 0 {
                            myQrSection
                        } else {
                            importContactSection
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 30)
                }
            }
        }
        .alert("永久リンクを再発行しますか？", isPresented: $showRevokeAlert) {
            Button("再発行する (旧リンクを無効化)", role: .destructive) {
                ContactExchangeManager.shared.regeneratePermanentInvite()
                let generator = UINotificationFeedbackGenerator()
                generator.notificationOccurred(.success)
            }
            Button("キャンセル", role: .cancel) {}
        } message: {
            Text("再発行すると、これまでに共有したQRコードや招待URLはすべて即座に無効化されます。新しく発行されたURLのみ追加可能になります。")
        }
    }

    private var myQrSection: some View {
        let invite = ContactExchangeManager.shared.createInvite(
            nickname: appState.myNickname,
            nostrPubkey: "nostr_hex_sample",
            edPubkey: "ed_hex_sample",
            uuid: appState.myUuid,
            type: selectedType,
            durationSeconds: selectedDurationSeconds
        )
        let nemixUri = ContactExchangeManager.shared.payloadToNemixUri(invite)
        let webUrl = ContactExchangeManager.shared.payloadToWebUrl(invite)

        return VStack(spacing: 16) {
            // Type Selector: Permanent vs Expiring
            HStack(spacing: 10) {
                Button(action: {
                    selectedType = .permanent
                }) {
                    HStack {
                        if selectedType == .permanent {
                            Image(systemName: "checkmark.circle.fill")
                        }
                        Text("永久リンク (再発行可)")
                            .font(.system(size: 13, weight: .bold))
                    }
                    .foregroundColor(selectedType == .permanent ? .white : .white.opacity(0.7))
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity)
                    .background(
                        Capsule()
                            .fill(selectedType == .permanent ? LiquidGlassTheme.primaryAccent : Color.white.opacity(0.08))
                    )
                }

                Button(action: {
                    selectedType = .expiring
                }) {
                    HStack {
                        if selectedType == .expiring {
                            Image(systemName: "timer")
                        }
                        Text("時間制限付き")
                            .font(.system(size: 13, weight: .bold))
                    }
                    .foregroundColor(selectedType == .expiring ? .white : .white.opacity(0.7))
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity)
                    .background(
                        Capsule()
                            .fill(selectedType == .expiring ? LiquidGlassTheme.primaryAccent : Color.white.opacity(0.08))
                    )
                }
            }

            // Duration selector if Expiring
            if selectedType == .expiring {
                HStack(spacing: 8) {
                    ForEach([
                        (600.0, "10分"),
                        (3600.0, "1時間"),
                        (86400.0, "24時間")
                    ], id: \.0) { sec, label in
                        Button(action: { selectedDurationSeconds = sec }) {
                            Text(label)
                                .font(.system(size: 12, weight: selectedDurationSeconds == sec ? .bold : .medium))
                                .foregroundColor(selectedDurationSeconds == sec ? .white : .white.opacity(0.7))
                                .padding(.horizontal, 14)
                                .padding(.vertical, 6)
                                .background(
                                    Capsule()
                                        .fill(selectedDurationSeconds == sec ? LiquidGlassTheme.secondaryAccent : Color.white.opacity(0.08))
                                )
                        }
                    }
                }
            }

            // Liquid Glass QR Frame
            if let qrImage = ContactExchangeManager.shared.generateQRCodeImage(from: nemixUri, targetSize: 440) {
                ZStack {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .fill(Color.white)
                        .frame(width: 240, height: 240)
                        .shadow(color: Color.black.opacity(0.2), radius: 14, x: 0, y: 6)

                    Image(uiImage: qrImage)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 210, height: 210)
                }
                .padding(.vertical, 8)
            }

            // Status Badge
            if selectedType == .permanent {
                LiquidGlassBadge(
                    text: "永久有効（いつでも再発行で旧リンク無効化可能）",
                    icon: "lock.shield.fill",
                    tint: LiquidGlassTheme.primaryAccent
                )
            } else {
                let expiryDate = Date().addingTimeInterval(selectedDurationSeconds)
                let formatter = DateFormatter()
                formatter.dateFormat = "M/d HH:mm"
                LiquidGlassBadge(
                    text: "有効期限: \(formatter.string(from: expiryDate)) まで",
                    icon: "clock.fill",
                    tint: Color.orange
                )
            }

            // Copy & Share URL Buttons
            HStack(spacing: 12) {
                LiquidGlassButton(title: "URLをコピー", icon: "doc.on.doc", accent: LiquidGlassTheme.secondaryAccent) {
                    UIPasteboard.general.string = webUrl
                    let generator = UINotificationFeedbackGenerator()
                    generator.notificationOccurred(.success)
                }

                LiquidGlassButton(title: "URLを共有", icon: "square.and.arrow.up", accent: LiquidGlassTheme.primaryAccent) {
                    if let url = URL(string: webUrl) {
                        shareUrlToExport = url
                        let activityVC = UIActivityViewController(activityItems: ["nemixで連絡先を追加しましょう！\n\(webUrl)"], applicationActivities: nil)
                        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                           let rootVC = windowScene.windows.first?.rootViewController {
                            rootVC.present(activityVC, animated: true)
                        }
                    }
                }
            }

            if selectedType == .permanent {
                Button(action: { showRevokeAlert = true }) {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .font(.system(size: 13))
                        Text("リンク・QRコードを再発行する（以前のリンクを無効化）")
                            .font(.system(size: 12, weight: .semibold))
                    }
                    .foregroundColor(Color(red: 1.0, green: 0.45, blue: 0.45))
                    .padding(.top, 4)
                }
            }
        }
    }

    private var importContactSection: some View {
        VStack(spacing: 16) {
            LiquidGlassCard {
                VStack(alignment: .leading, spacing: 12) {
                    Text("友人の招待URLまたはコードを入力")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.white)

                    TextField("nemix://contact/add?... または https://nemix.im/c#...", text: $manualInputUrl)
                        .font(.system(size: 13))
                        .foregroundColor(.white)
                        .padding(10)
                        .background(Color.white.opacity(0.08))
                        .cornerRadius(12)

                    LiquidGlassButton(title: "URLを検証して確認", icon: "checkmark.circle", accent: LiquidGlassTheme.primaryAccent) {
                        switch ContactExchangeManager.shared.validateAndParse(manualInputUrl) {
                        case .valid(let payload):
                            isError = false
                            parsedResultText = "✅ 連絡先が見つかりました: \(payload.nickname) (UUID: \(payload.uuid.prefix(8))...)"
                        case .expired(let date):
                            isError = true
                            let formatter = DateFormatter()
                            formatter.dateFormat = "yyyy/MM/dd HH:mm"
                            parsedResultText = "⚠️ この招待リンクは有効期限が切れています (\(formatter.string(from: date)))"
                        case .revoked:
                            isError = true
                            parsedResultText = "⚠️ この招待リンクは相手側による再発行のため無効化されています"
                        case .error(let msg):
                            isError = true
                            parsedResultText = "❌ \(msg)"
                        }
                    }
                }
            }

            if let result = parsedResultText {
                Text(result)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(isError ? Color.orange : Color.green)
                    .padding(12)
                    .liquidGlass(cornerRadius: 14)
            }
        }
    }
}

