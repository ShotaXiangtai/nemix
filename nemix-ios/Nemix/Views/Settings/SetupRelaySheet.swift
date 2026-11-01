import SwiftUI

// MARK: - Initial Setup Prompt: P2P Relay Node (Serverless Gateway) with Pros & Cons
// Directly fulfills user request:
// - Prompts during setup whether to turn ON the P2P relay node feature
// - Explains the pros (reach distant people over the internet even when offline mesh isn't reachable)
// - Explains the cons (slight battery & bandwidth usage in background)

public struct SetupRelaySheet: View {
    @EnvironmentObject var appState: AppState
    @Binding var isPresented: Bool

    @State private var isRelaySelected: Bool = true

    public init(isPresented: Binding<Bool>) {
        self._isPresented = isPresented
    }

    public var body: some View {
        ZStack {
            LiquidMeshBackground()

            VStack(spacing = 20) {
                // Header
                VStack(spacing = 8) {
                    ZStack {
                        Circle()
                            .fill(LiquidGlassTheme.primaryAccent.opacity(0.2))
                            .frame(width: 70, height: 70)
                        Image(systemName: "point.3.filled.connected.trianglepath.dotted")
                            .font(.system(size: 32, weight: .bold))
                            .foregroundColor(LiquidGlassTheme.primaryAccent)
                    }

                    Text("P2P 遠隔リレー / メッシュ中継設定")
                        .font(.system(size: 20, weight: .black, design: .rounded))
                        .foregroundColor(.white)

                    Text("あなたのiPhoneを自律型中継ノードとして動かすか選択してください（設定からいつでも変更可能です）")
                        .font(.system(size: 12.5))
                        .foregroundColor(.white.opacity(0.7))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20)
                }
                .padding(.top, 24)

                // Choice Cards
                VStack(spacing = 12) {
                    Button(action: { isRelaySelected = true }) {
                        HStack(spacing = 14) {
                            Image(systemName: isRelaySelected ? "checkmark.circle.fill" : "circle")
                                .font(.system(size: 20))
                                .foregroundColor(isRelaySelected ? LiquidGlassTheme.primaryAccent : .white.opacity(0.4))

                            VStack(alignment: .leading, spacing: 3) {
                                Text("リレーノードとして参加する (推奨)")
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundColor(.white)
                                Text("インターネット接続時に遠隔中継を有効化")
                                    .font(.system(size: 12))
                                    .foregroundColor(.white.opacity(0.7))
                            }
                            Spacer()
                        }
                        .padding(16)
                        .liquidGlass(cornerRadius: 18)
                    }
                    .buttonStyle(.plain)

                    Button(action: { isRelaySelected = false }) {
                        HStack(spacing = 14) {
                            Image(systemName: !isRelaySelected ? "checkmark.circle.fill" : "circle")
                                .font(.system(size: 20))
                                .foregroundColor(!isRelaySelected ? LiquidGlassTheme.secondaryAccent : .white.opacity(0.4))

                            VStack(alignment: .leading, spacing: 3) {
                                Text("通常モード（自端末のみ利用）")
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundColor(.white)
                                Text("他端末のパケット中継を行いません")
                                    .font(.system(size: 12))
                                    .foregroundColor(.white.opacity(0.7))
                            }
                            Spacer()
                        }
                        .padding(16)
                        .liquidGlass(cornerRadius: 18)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 20)

                // Pros & Cons Card
                LiquidGlassCard {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing = 6) {
                            Image(systemName: "hand.thumbsup.fill")
                                .foregroundColor(LiquidGlassTheme.primaryAccent)
                            Text("【メリット】")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(LiquidGlassTheme.primaryAccent)
                        }
                        Text("・近くにいなくても届く: Bluetoothが直接届かない遠方の相手とも、インターネット経由で暗号化メッセージや通話をシームレスに中継\n・ネットワーク貢献: 被災時などオフラインの友人端末をゼロ知識で支援し、中央サーバーのない分散網を強化")
                            .font(.system(size: 12))
                            .foregroundColor(.white.opacity(0.85))
                            .lineSpacing(3)

                        Divider().background(Color.white.opacity(0.2))

                        HStack(spacing = 6) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(Color.orange)
                            Text("【留意点（デメリット）】")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(Color.orange)
                        }
                        Text("・バッテリー消費: バックグラウンドでのP2P接続維持により、通常時よりわずかにバッテリーを消費します\n・通信量: Wi-Fi未接続時に微量のモバイル通信を使用します（設定でWi-Fi限定も可能）")
                            .font(.system(size: 12))
                            .foregroundColor(.white.opacity(0.75))
                            .lineSpacing(3)
                    }
                }
                .padding(.horizontal, 20)

                Spacer()

                // Continue Button
                LiquidGlassButton(title: "設定して nemix をはじめる", icon: "arrow.right", accent: LiquidGlassTheme.primaryAccent) {
                    appState.isRelayNodeEnabled = isRelaySelected
                    appState.isSetupCompleted = true
                    isPresented = false
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 20)
            }
        }
    }
}

