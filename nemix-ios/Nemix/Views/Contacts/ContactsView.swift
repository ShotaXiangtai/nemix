import SwiftUI

// MARK: - iOS Contacts View
// Features:
// - Prominent Liquid Glass Card to launch QR & URL Contact Exchange
// - UUID display with 1-tap copy & rotate
// - Contact list with online indicators & direct chat start

public struct ContactsView: View {
    @EnvironmentObject var appState: AppState
    @State private var showContactExchange = false
    @State private var searchUuidText = ""
    @State private var selectedContactToChat: ContactItem?
    @State private var isNavigatingToChat = false

    public var body: some View {
        NavigationStack {
            ZStack {
                LiquidMeshBackground()

                VStack(spacing = 14) {
                    // Top Bar
                    HStack {
                        Text("連絡先")
                            .font(.system(size: 24, weight: .black, design: .rounded))
                            .foregroundColor(.white)
                        Spacer()
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 12)

                    ScrollView {
                        VStack(spacing = 14) {
                            // Hero Contact Exchange Card
                            Button(action: { showContactExchange = true }) {
                                HStack(spacing = 14) {
                                    ZStack {
                                        Circle()
                                            .fill(LiquidGlassTheme.primaryAccent.opacity(0.25))
                                            .frame(width: 48, height: 48)
                                        Image(systemName: "qrcode.viewfinder")
                                            .font(.system(size: 22, weight: .bold))
                                            .foregroundColor(LiquidGlassTheme.primaryAccent)
                                    }

                                    VStack(alignment: .leading, spacing: 3) {
                                        Text("QR・URLで連絡先交換")
                                            .font(.system(size: 15, weight: .bold))
                                            .foregroundColor(.white)
                                        Text("いつでも再発行できる永久リンク / 時限リンク")
                                            .font(.system(size: 11.5))
                                            .foregroundColor(.white.opacity(0.7))
                                    }

                                    Spacer()

                                    Image(systemName: "chevron.right")
                                        .foregroundColor(.white.opacity(0.4))
                                }
                                .padding(16)
                                .liquidGlass(cornerRadius: 20)
                            }
                            .buttonStyle(.plain)

                            // My UUID Card
                            LiquidGlassCard {
                                VStack(alignment: .leading, spacing: 8) {
                                    HStack {
                                        Text("私のランダムUUID")
                                            .font(.system(size: 12, weight: .bold))
                                            .foregroundColor(.white.opacity(0.7))
                                        Spacer()
                                        Button(action: {
                                            UIPasteboard.general.string = appState.myUuid
                                        }) {
                                            HStack(spacing = 4) {
                                                Image(systemName: "doc.on.doc")
                                                Text("コピー")
                                            }
                                            .font(.system(size: 11, weight: .bold))
                                            .foregroundColor(LiquidGlassTheme.secondaryAccent)
                                        }
                                    }

                                    Text(appState.myUuid)
                                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                                        .foregroundColor(.white)
                                }
                            }

                            // Contacts List
                            VStack(alignment: .leading, spacing: 10) {
                                Text("登録済みの連絡先 (\(appState.contacts.count))")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(.white.opacity(0.6))
                                    .padding(.horizontal, 4)

                                ForEach(appState.contacts) { contact in
                                    Button(action: {
                                        selectedContactToChat = contact
                                        isNavigatingToChat = true
                                    }) {
                                        HStack(spacing = 12) {
                                            ZStack(alignment: .bottomTrailing) {
                                                Circle()
                                                    .fill(LiquidGlassTheme.secondaryAccent)
                                                    .frame(width: 44, height: 44)
                                                Text(contact.nickname.prefix(1))
                                                    .font(.system(size: 18, weight: .bold))
                                                    .foregroundColor(.white)

                                                if contact.isOnline {
                                                    Circle()
                                                        .fill(Color.green)
                                                        .frame(width: 12, height: 12)
                                                        .overlay(Circle().stroke(Color.black, lineWidth: 2))
                                                }
                                            }

                                            VStack(alignment: .leading, spacing: 2) {
                                                Text(contact.nickname)
                                                    .font(.system(size: 15, weight: .bold))
                                                    .foregroundColor(.white)
                                                Text("UUID: \(contact.uuid)")
                                                    .font(.system(size: 11))
                                                    .foregroundColor(.white.opacity(0.55))
                                            }

                                            Spacer()

                                            Image(systemName: "message.fill")
                                                .foregroundColor(LiquidGlassTheme.primaryAccent)
                                                .font(.system(size: 16))
                                        }
                                        .padding(.horizontal, 14)
                                        .padding(.vertical, 10)
                                        .liquidGlass(cornerRadius: 16)
                                    }
                                    .buttonStyle(.plain)
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
            .navigationDestination(isPresented: $isNavigatingToChat) {
                if let c = selectedContactToChat {
                    ChatDetailView(chatId: c.id, chatTitle: c.nickname, subtitle: "1-on-1 P2P暗号化", isGroup: false)
                }
            }
        }
    }
}

