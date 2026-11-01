import SwiftUI

// MARK: - Chat Detail Screen matching User Screenshot 3 (media_1793533281780_37aa9d2c.jpg)
// Features:
// - Top bar with back arrow, topic name, subtitle ("在 写真共有"), 3-dots menu
// - Center celebration card when empty: "🎉 就快完成了！/ 发送消息以开始话题。"
// - Liquid Glass message bubbles
// - Bottom input bar with attachment paperclip, text input, microphone / send button

public struct ChatDetailView: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var appState: AppState

    public let chatId: String
    public let chatTitle: String
    public let subtitle: String
    public let isGroup: Bool

    @State private var inputText: String = ""
    @State private var showAttachmentOptions = false
    @State private var isScreenshotBlocked = false

    public init(chatId: String, chatTitle: String, subtitle: String, isGroup: Bool) {
        self.chatId = chatId
        self.chatTitle = chatTitle
        self.subtitle = subtitle
        self.isGroup = isGroup
    }

    public var body: some View {
        ZStack {
            LiquidMeshBackground()

            VStack(spacing: 0) {
                // Top Custom Navigation Bar matching Screenshot 3
                HStack(spacing: 12) {
                    Button(action: { dismiss() }) {
                        Image(systemName: "arrow.left")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                            .padding(8)
                    }

                    // Topic / Chat Avatar
                    ZStack {
                        Circle()
                            .fill(Color.white.opacity(0.12))
                            .frame(width: 40, height: 40)
                        Image(systemName: isGroup ? "number" : "person.fill")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                    }

                    VStack(alignment: .leading, spacing: 2) {
                        Text(chatTitle)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.white)
                            .lineLimit(1)
                        Text(subtitle)
                            .font(.system(size: 11.5))
                            .foregroundColor(.white.opacity(0.65))
                    }

                    Spacer()

                    // Screenshot Protection Toggle Pill
                    Button(action: {
                        isScreenshotBlocked.toggle()
                        let generator = UINotificationFeedbackGenerator()
                        generator.notificationOccurred(isScreenshotBlocked ? .success : .warning)
                    }) {
                        Image(systemName: isScreenshotBlocked ? "lock.shield.fill" : "lock.shield")
                            .font(.system(size: 16))
                            .foregroundColor(isScreenshotBlocked ? Color.orange : .white.opacity(0.6))
                            .padding(8)
                    }

                    Button(action: {}) {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.white.opacity(0.85))
                            .padding(8)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(.ultraThinMaterial)

                // Chat Messages Feed
                let msgs = appState.messages[chatId] ?? []

                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 10) {
                            if msgs.isEmpty {
                                // Center Celebration Card matching Screenshot 3
                                Spacer(minLength: 120)

                                VStack(spacing: 12) {
                                    Text("🎉")
                                        .font(.system(size: 48))

                                    Text("就快完成了！")
                                        .font(.system(size: 17, weight: .bold, design: .rounded))
                                        .foregroundColor(.white)

                                    Text("发送消息以开始话题。")
                                        .font(.system(size: 13))
                                        .foregroundColor(.white.opacity(0.7))
                                }
                                .padding(.horizontal, 32)
                                .padding(.vertical, 24)
                                .liquidGlass(cornerRadius: 22)
                                .padding(.horizontal, 40)

                                Spacer(minLength: 120)
                            } else {
                                ForEach(msgs) { msg in
                                    MessageBubble(message: msg)
                                        .id(msg.id)
                                }
                            }
                        }
                        .padding(.horizontal, 14)
                        .padding(.top, 14)
                        .padding(.bottom, 20)
                    }
                    .onChange(of: msgs.count) { _ in
                        if let lastId = msgs.last?.id {
                            withAnimation {
                                proxy.scrollTo(lastId, anchor: .bottom)
                            }
                        }
                    }
                }

                // Bottom Input Bar matching Screenshot 3
                HStack(spacing: 10) {
                    Button(action: { showAttachmentOptions = true }) {
                        Image(systemName: "paperclip")
                            .font(.system(size: 20))
                            .foregroundColor(.white.opacity(0.75))
                            .padding(6)
                    }

                    TextField("输入消息", text: $inputText)
                        .font(.system(size: 15))
                        .foregroundColor(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .liquidGlass(cornerRadius: 20, shadowRadius: 6)

                    if inputText.trimmingCharacters(in: .whitespaces).isEmpty {
                        // Voice note microphone
                        Button(action: {}) {
                            Image(systemName: "mic.fill")
                                .font(.system(size: 18))
                                .foregroundColor(.white)
                                .frame(width: 42, height: 42)
                                .background(Circle().fill(LiquidGlassTheme.primaryAccent))
                                .shadow(color: LiquidGlassTheme.primaryAccent.opacity(0.4), radius: 6, x: 0, y: 3)
                        }
                    } else {
                        // Send button
                        Button(action: {
                            sendMessage()
                        }) {
                            Image(systemName: "arrow.up")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.white)
                                .frame(width: 42, height: 42)
                                .background(Circle().fill(LiquidGlassTheme.secondaryAccent))
                                .shadow(color: LiquidGlassTheme.secondaryAccent.opacity(0.4), radius: 6, x: 0, y: 3)
                        }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(.ultraThinMaterial)
            }
        }
        .navigationBarHidden(true)
        .confirmationDialog("ファイルを送信", isPresented: $showAttachmentOptions, titleVisibility: .visible) {
            Button("📷 写真・画像 (EXIF自動除去)") {}
            Button("📍 現在地 (OS標準・追跡ゼロ)") {}
            Button("キャンセル", role: .cancel) {}
        }
    }

    private func sendMessage() {
        let content = inputText.trimmingCharacters(in: .whitespaces)
        guard !content.isEmpty else { return }
        appState.sendMessage(to: chatId, content: content)
        inputText = ""
    }
}

// MARK: - Liquid Glass Message Bubble
struct MessageBubble: View {
    let message: ChatMessage

    var body: some View {
        HStack {
            if message.isSelf { Spacer(minLength: 40) }

            VStack(alignment: message.isSelf ? .trailing : .leading, spacing: 4) {
                if !message.isSelf {
                    Text(message.senderName)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(LiquidGlassTheme.secondaryAccent)
                }

                Text(message.content)
                    .font(.system(size: 14.5))
                    .foregroundColor(.white)

                Text(formatTime(message.timestamp))
                    .font(.system(size: 10))
                    .foregroundColor(.white.opacity(0.55))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(message.isSelf ? LiquidGlassTheme.primaryAccent.opacity(0.85) : Color.white.opacity(0.12))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(Color.white.opacity(message.isSelf ? 0.5 : 0.2), lineWidth: 0.8)
                    )
            )

            if !message.isSelf { Spacer(minLength: 40) }
        }
    }

    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
}

