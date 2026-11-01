import SwiftUI

// MARK: - iOS Settings View with Liquid Glass Aesthetic
// Features:
// - P2P Relay Node toggle with Pros & Cons
// - Stealth Mode (Nearby Invisibility)
// - Terms of Service & Legal declarations
// - Developer GitHub link (ShotaXiangtai)

public struct SettingsView: View {
    @EnvironmentObject var appState: AppState
    @State private var showTermsSheet = false
    @State private var showContactExchange = false

    public var body: some View {
        NavigationStack {
            ZStack {
                LiquidMeshBackground()

                VStack(spacing = 14) {
                    HStack {
                        Text("設定")
                            .font(.system(size: 24, weight: .black, design: .rounded))
                            .foregroundColor(.white)
                        Spacer()
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 12)

                    ScrollView {
                        VStack(spacing = 14) {
                            // P2P Relay Node Card with Pros & Cons
                            LiquidGlassCard {
                                VStack(alignment: .leading, spacing: 10) {
                                    HStack {
                                        Image(systemName: "point.3.filled.connected.trianglepath.dotted")
                                            .foregroundColor(LiquidGlassTheme.primaryAccent)
                                            .font(.system(size: 18))
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text("P2P 遠隔リレー / メッシュ中継")
                                                .font(.system(size: 14, weight: .bold))
                                                .foregroundColor(.white)
                                            Text("オフライン端末の暗号化パケットを遠隔中継")
                                                .font(.system(size: 11))
                                                .foregroundColor(.white.opacity(0.6))
                                        }
                                        Spacer()
                                        Toggle("", isOn: $appState.isRelayNodeEnabled)
                                            .labelsHidden()
                                            .tint(LiquidGlassTheme.primaryAccent)
                                    }

                                    Divider().background(Color.white.opacity(0.15))

                                    VStack(alignment: .leading, spacing: 4) {
                                        Text("【メリット】近くにいなくてもネット経由で届く・分散網貢献")
                                            .font(.system(size: 10.5, weight: .medium))
                                            .foregroundColor(LiquidGlassTheme.primaryAccent)
                                        Text("【留意点】バックグラウンドでの微小なバッテリー・通信量消費")
                                            .font(.system(size: 10.5))
                                            .foregroundColor(.white.opacity(0.65))
                                    }
                                }
                            }

                            // Nearby Stealth Mode Card
                            LiquidGlassCard {
                                HStack {
                                    Image(systemName: appState.isStealthMode ? "eye.slash.fill" : "eye.fill")
                                        .foregroundColor(appState.isStealthMode ? Color.orange : LiquidGlassTheme.secondaryAccent)
                                        .font(.system(size: 18))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("周辺非公開（ステルスモード）")
                                            .font(.system(size: 14, weight: .bold))
                                            .foregroundColor(.white)
                                        Text(appState.isStealthMode ? "近距離検出を停止・QR/UUID追加のみ許可" : "周辺のnemix端末から検出を許可")
                                            .font(.system(size: 11))
                                            .foregroundColor(.white.opacity(0.6))
                                    }
                                    Spacer()
                                    Toggle("", isOn: $appState.isStealthMode)
                                        .labelsHidden()
                                        .tint(Color.orange)
                                        .onChange(of: appState.isStealthMode) { enabled in
                                            P2PMeshNetwork.shared.setStealthMode(enabled)
                                        }
                                }
                            }

                            // QR & URL Contact Exchange Action Row
                            Button(action: { showContactExchange = true }) {
                                HStack(spacing = 14) {
                                    Image(systemName: "qrcode.viewfinder")
                                        .foregroundColor(LiquidGlassTheme.primaryAccent)
                                        .font(.system(size: 18))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("QR・URLで連絡先交換")
                                            .font(.system(size: 14, weight: .bold))
                                            .foregroundColor(.white)
                                        Text("永久リンク（再発行可）/ 時間制限付き")
                                            .font(.system(size: 11))
                                            .foregroundColor(.white.opacity(0.6))
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .foregroundColor(.white.opacity(0.4))
                                }
                                .padding(16)
                                .liquidGlass(cornerRadius: 18)
                            }
                            .buttonStyle(.plain)

                            // Terms of Service & Privacy Policy Row
                            Button(action: { showTermsSheet = true }) {
                                HStack(spacing = 14) {
                                    Image(systemName: "doc.text.fill")
                                        .foregroundColor(Color.white.opacity(0.8))
                                        .font(.system(size: 18))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("利用規約・プライバシーポリシー")
                                            .font(.system(size: 14, weight: .bold))
                                            .foregroundColor(.white)
                                        Text("電波法・航空法・免責事項・3つの目的")
                                            .font(.system(size: 11))
                                            .foregroundColor(.white.opacity(0.6))
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .foregroundColor(.white.opacity(0.4))
                                }
                                .padding(16)
                                .liquidGlass(cornerRadius: 18)
                            }
                            .buttonStyle(.plain)

                            // Developer GitHub Card
                            LiquidGlassCard {
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("開発者情報")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundColor(.white.opacity(0.6))
                                    HStack {
                                        Text("ShotaXiangtai (GitHub)")
                                            .font(.system(size: 14, weight: .bold))
                                            .foregroundColor(.white)
                                        Spacer()
                                        Link("github.com/ShotaXiangtai ↗", destination: URL(string: "https://github.com/ShotaXiangtai")!)
                                            .font(.system(size: 12, weight: .bold))
                                            .foregroundColor(LiquidGlassTheme.secondaryAccent)
                                    }
                                }
                            }
                        }
                        .padding(.horizontal, 20)
                        .padding(.bottom, 90)
                    }
                }
            }
            .sheet(isPresented: $showContactExchange) {
                ContactExchangeView()
            }
            .sheet(isPresented: $showTermsSheet) {
                TermsSheetView()
            }
        }
    }
}

// MARK: - Terms of Service Sheet
struct TermsSheetView: View {
    @Environment(\.dismiss) var dismiss

    var body: some View {
        ZStack {
            LiquidMeshBackground()
            VStack(spacing = 16) {
                HStack {
                    Text("利用規約・免責事項")
                        .font(.system(size: 18, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                    Spacer()
                    Button("閉じる") { dismiss() }
                        .foregroundColor(LiquidGlassTheme.primaryAccent)
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)

                ScrollView {
                    LiquidGlassCard {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("【本アプリの目的】")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(LiquidGlassTheme.primaryAccent)
                            Text("本アプリは、災害時にも機能する「オフライン通信の確保」、帯域を節約する「トラフィック負荷の軽減」、特定企業に縛られない「プラットフォーム依存の脱却」を同時に達成するサーバーレスSNSです。")
                                .font(.system(size: 12.5))
                                .foregroundColor(.white.opacity(0.85))
                                .lineSpacing(3)

                            Divider().background(Color.white.opacity(0.2))

                            Text("【電波法・航空法等の遵守】")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(.white)
                            Text("航空機内では機長および客室乗務員の指示に従い、電波を発する機器の使用が制限されている時間帯は機内モードに設定してください。違法な通信傍受や迷惑行為は禁止されています。")
                                .font(.system(size: 12.5))
                                .foregroundColor(.white.opacity(0.8))
                                .lineSpacing(3)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 30)
                }
            }
        }
    }
}

