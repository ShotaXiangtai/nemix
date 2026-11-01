import SwiftUI

// MARK: - iOS Profile View (Tab 3) with Liquid Glass

public struct ProfileView: View {
    @EnvironmentObject var appState: AppState
    @State private var nameInput: String = ""
    @State private var showSavedAlert = false

    public var body: some View {
        NavigationStack {
            ZStack {
                LiquidMeshBackground()

                VStack(spacing: 16) {
                    HStack {
                        Text("プロフィール")
                            .font(.system(size: 24, weight: .black, design: .rounded))
                            .foregroundColor(.white)
                        Spacer()
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 12)

                    ScrollView {
                        VStack(spacing: 20) {
                            // Avatar
                            ZStack {
                                Circle()
                                    .fill(LiquidGlassTheme.primaryAccent)
                                    .frame(width: 90, height: 90)
                                    .overlay(Circle().stroke(Color.white.opacity(0.4), lineWidth: 2))
                                    .shadow(color: LiquidGlassTheme.primaryAccent.opacity(0.45), radius: 12, x: 0, y: 6)

                                Text(appState.myNickname.prefix(1))
                                    .font(.system(size: 38, weight: .bold, design: .rounded))
                                    .foregroundColor(.white)
                            }
                            .padding(.top, 10)

                            // Name Input
                            LiquidGlassCard {
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("表示名（ニックネーム）")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundColor(.white.opacity(0.7))

                                    TextField("表示名", text: $nameInput)
                                        .font(.system(size: 15))
                                        .foregroundColor(.white)
                                        .padding(10)
                                        .background(Color.white.opacity(0.08))
                                        .cornerRadius(12)
                                }
                            }

                            // UUID Info
                            LiquidGlassCard {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("P2P 識別用 UUID (電話番号・メアド不要)")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundColor(.white.opacity(0.7))
                                    Text(appState.myUuid)
                                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                                        .foregroundColor(.white)
                                }
                            }

                            LiquidGlassButton(title: "変更を保存", icon: "checkmark", accent: LiquidGlassTheme.primaryAccent) {
                                let trimmed = nameInput.trimmingCharacters(in: .whitespaces)
                                if !trimmed.isEmpty {
                                    appState.myNickname = trimmed
                                }
                                showSavedAlert = true
                            }
                            .padding(.top, 8)
                        }
                        .padding(.horizontal, 20)
                        .padding(.bottom, 90)
                    }
                }
            }
            .onAppear {
                nameInput = appState.myNickname
            }
            .alert("プロフィールを保存しました", isPresented: $showSavedAlert) {
                Button("OK") {}
            }
        }
    }
}

