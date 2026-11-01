import SwiftUI

// MARK: - Group Topics Screen matching User Screenshot 2 (media_1793533281777_6d6d43e8.jpg)
// Features:
// - Group header with Avatar, Member count, Search, 3-dots menu
// - Clean list of topics / channels with unread badge and latest message preview
// - Floating Action Button "+" to create a new topic
// - Tapping a topic navigates to ChatDetailView (matching Screenshot 3)

public struct GroupTopicsView: View {
    @Environment(\.dismiss) var dismiss
    public let group: P2PGroupItem

    @State private var selectedChannel: P2PChannel?
    @State private var isNavigatingToChat = false
    @State private var showNewTopicDialog = false
    @State private var newTopicName = ""
    @State private var showGroupMenu = false

    public init(group: P2PGroupItem) {
        self.group = group
    }

    public var body: some View {
        ZStack {
            LiquidMeshBackground()

            VStack(spacing: 0) {
                // Top Custom Navigation Bar matching Screenshot 2
                HStack(spacing: 12) {
                    Button(action: { dismiss() }) {
                        Image(systemName: "arrow.left")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                            .padding(8)
                    }

                    // Group Avatar
                    ZStack {
                        Circle()
                            .fill(LiquidGlassTheme.primaryAccent)
                            .frame(width: 42, height: 42)
                            .overlay(Circle().stroke(Color.white.opacity(0.35), lineWidth: 1))
                        Text(group.avatarInitial)
                            .font(.system(size: 18, weight: .bold, design: .rounded))
                            .foregroundColor(.white)
                    }

                    VStack(alignment: .leading, spacing: 2) {
                        Text(group.name)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.white)
                            .lineLimit(1)
                        Text("\(group.memberCount)人のメンバー · P2P暗号化")
                            .font(.system(size: 11.5))
                            .foregroundColor(.white.opacity(0.65))
                    }

                    Spacer()

                    Button(action: {}) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundColor(.white.opacity(0.85))
                            .padding(8)
                    }

                    Button(action: { showGroupMenu = true }) {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.white.opacity(0.85))
                            .padding(8)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(.ultraThinMaterial)

                // Topic Items List matching Screenshot 2
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(group.channels) { channel in
                            Button(action: {
                                selectedChannel = channel
                                isNavigatingToChat = true
                            }) {
                                HStack(spacing: 14) {
                                    // Topic Icon
                                    ZStack {
                                        Circle()
                                            .fill(channel.icon.contains("message") ? LiquidGlassTheme.secondaryAccent.opacity(0.25) : Color.white.opacity(0.12))
                                            .frame(width: 44, height: 44)
                                        Image(systemName: channel.icon)
                                            .font(.system(size: 18, weight: .semibold))
                                            .foregroundColor(channel.icon.contains("message") ? LiquidGlassTheme.secondaryAccent : .white.opacity(0.85))
                                    }

                                    VStack(alignment: .leading, spacing: 4) {
                                        HStack {
                                            Text(channel.name)
                                                .font(.system(size: 15, weight: .bold))
                                                .foregroundColor(.white)
                                            Spacer()
                                            Text("金曜日")
                                                .font(.system(size: 11.5))
                                                .foregroundColor(.white.opacity(0.5))
                                        }

                                        HStack {
                                            if channel.id == "osaka_trip" {
                                                Text("您: [画像] 3枚の写真")
                                                    .font(.system(size: 12.5))
                                                    .foregroundColor(.white.opacity(0.72))
                                            } else {
                                                Text("このトピックが作成されました")
                                                    .font(.system(size: 12.5))
                                                    .foregroundColor(.white.opacity(0.6))
                                            }
                                            Spacer()

                                            if channel.unreadCount > 0 {
                                                Text("\(channel.unreadCount)")
                                                    .font(.system(size: 11, weight: .bold))
                                                    .foregroundColor(.white)
                                                    .padding(.horizontal, 8)
                                                    .padding(.vertical, 2)
                                                    .background(Capsule().fill(LiquidGlassTheme.secondaryAccent))
                                            }
                                        }
                                    }
                                }
                                .padding(.horizontal, 14)
                                .padding(.vertical, 12)
                                .liquidGlass(cornerRadius: 18)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 14)
                    .padding(.bottom, 90)
                }

                Spacer()
            }

            // Floating "+" Button to add new Topic (Screenshot 2 bottom right)
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    Button(action: { showNewTopicDialog = true }) {
                        Image(systemName: "plus.bubble.fill")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 56, height: 56)
                            .background(
                                Circle()
                                    .fill(LiquidGlassTheme.primaryAccent)
                                    .overlay(Circle().stroke(Color.white.opacity(0.6), lineWidth: 1.5))
                                    .shadow(color: LiquidGlassTheme.primaryAccent.opacity(0.55), radius: 14, x: 0, y: 6)
                            )
                    }
                    .padding(.trailing, 20)
                    .padding(.bottom, 24)
                }
            }
        }
        .navigationBarHidden(true)
        .navigationDestination(isPresented: $isNavigatingToChat) {
            if let ch = selectedChannel {
                ChatDetailView(
                    chatId: "\(group.id):\(ch.id)",
                    chatTitle: "# \(ch.name)",
                    subtitle: "在 \(group.name)",
                    isGroup: true
                )
            }
        }
        .alert("新しいトピックを作成", isPresented: $showNewTopicDialog) {
            TextField("トピック名（例: 雑談、連絡用）", text: $newTopicName)
            Button("作成") {
                newTopicName = ""
            }
            Button("キャンセル", role: .cancel) {}
        }
    }
}

