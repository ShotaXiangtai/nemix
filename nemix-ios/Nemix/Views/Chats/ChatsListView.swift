import SwiftUI

// MARK: - Chats List View matching User Screenshot 1 (media_1793533281777_46e6efd9.jpg)
// Features:
// - Liquid Glass materials & subtle fluid background
// - Top Search bar & Horizontal filter pills ("すべて", "個人チャット", "グループ", "未読")
// - Pinned "📁 アーカイブしたチャット" row
// - Integrated list of 1-on-1 chats and multi-topic groups (e.g. "写真共有" with topics summary)
// - Floating Action Button "+" for new chat / group / QR exchange

public struct ChatsListView: View {
    @EnvironmentObject var appState: AppState
    @State private var selectedGroupForTopics: P2PGroupItem?
    @State private var selectedChatId: String?
    @State private var selectedChatName: String = ""
    @State private var isNavigatingToChat = false
    @State private var isNavigatingToTopics = false
    @State private var showNewMenu = false
    @State private var showContactExchange = false
    @State private var showCreateGroup = false

    public var body: some View {
        NavigationStack {
            ZStack {
                LiquidMeshBackground()

                VStack(spacing: 0) {
                    // Header Bar
                    HStack {
                        HStack(spacing: 8) {
                            Text("nemix")
                                .font(.system(size: 24, weight: .black, design: .rounded))
                                .foregroundColor(.white)
                            LiquidGlassBadge(text: "100% P2P", icon: "lock.shield.fill", tint: LiquidGlassTheme.primaryAccent)
                        }

                        Spacer()

                        Button(action: { showNewMenu = true }) {
                            Image(systemName: "ellipsis")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(.white.opacity(0.85))
                                .padding(8)
                                .liquidGlass(cornerRadius: 12)
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 12)
                    .padding(.bottom, 8)

                    // Search Bar
                    LiquidGlassSearchBar(text: $appState.searchQuery)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 10)

                    // Filter Pills matching Screenshot 1
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(HomeFilter.allCases, id: \.self) { filter in
                                Button(action: {
                                    let generator = UISelectionFeedbackGenerator()
                                    generator.selectionChanged()
                                    appState.selectedFilter = filter
                                }) {
                                    Text(filter.rawValue)
                                        .font(.system(size: 13, weight: appState.selectedFilter == filter ? .bold : .medium))
                                        .foregroundColor(appState.selectedFilter == filter ? .white : .white.opacity(0.7))
                                        .padding(.horizontal, 14)
                                        .padding(.vertical, 7)
                                        .background(
                                            Capsule()
                                                .fill(appState.selectedFilter == filter ? LiquidGlassTheme.primaryAccent : Color.white.opacity(0.08))
                                                .overlay(
                                                    Capsule()
                                                        .stroke(Color.white.opacity(appState.selectedFilter == filter ? 0.6 : 0.15), lineWidth: 1)
                                                )
                                        )
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 12)
                    }

                    // Chat List
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            // Pinned "已归档的聊天 / アーカイブしたチャット" row (Screenshot 1 top item)
                            ArchivedChatRow(archivedCount: 1)
                                .padding(.horizontal, 14)

                            ForEach(filteredConversations) { conv in
                                ConversationRow(conversation: conv) {
                                    if conv.isGroup, let grp = conv.groupData {
                                        selectedGroupForTopics = grp
                                        isNavigatingToTopics = true
                                    } else {
                                        selectedChatId = conv.id
                                        selectedChatName = conv.name
                                        isNavigatingToChat = true
                                    }
                                }
                                .padding(.horizontal, 14)
                            }
                        }
                        .padding(.bottom, 90) // clearance for bottom tab bar and FAB
                    }
                }

                // Floating Action Button matching Screenshot 1 "+" pencil
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Button(action: { showNewMenu = true }) {
                            Image(systemName: "square.and.pencil")
                                .font(.system(size: 20, weight: .bold))
                                .foregroundColor(.white)
                                .frame(width: 56, height: 56)
                                .background(
                                    Circle()
                                        .fill(LiquidGlassTheme.secondaryAccent)
                                        .overlay(
                                            Circle()
                                                .stroke(Color.white.opacity(0.65), lineWidth: 1.5)
                                        )
                                        .shadow(color: LiquidGlassTheme.secondaryAccent.opacity(0.55), radius: 14, x: 0, y: 6)
                                )
                        }
                        .padding(.trailing, 20)
                        .padding(.bottom, 24)
                    }
                }
            }
            .navigationDestination(isPresented: $isNavigatingToTopics) {
                if let grp = selectedGroupForTopics {
                    GroupTopicsView(group: grp)
                }
            }
            .navigationDestination(isPresented: $isNavigatingToChat) {
                if let chatId = selectedChatId {
                    ChatDetailView(
                        chatId: chatId,
                        chatTitle: selectedChatName,
                        subtitle: "P2P エンドツーエンド暗号化",
                        isGroup: false
                    )
                }
            }
            .sheet(isPresented: $showContactExchange) {
                ContactExchangeView()
            }
            .confirmationDialog("新しい会話を開始", isPresented: $showNewMenu, titleVisibility: .visible) {
                Button("📷 QR・URLで連絡先交換 (永久/時限)") { showContactExchange = true }
                Button("👥 新しいP2Pグループを作成") { showCreateGroup = true }
                Button("💬 ダイレクトチャットを開始") {}
                Button("キャンセル", role: .cancel) {}
            }
        }
    }

    private var filteredConversations: [ConversationItem] {
        appState.conversations.filter { conv in
            if !appState.searchQuery.isEmpty {
                if !conv.name.localizedCaseInsensitiveContains(appState.searchQuery) &&
                   !conv.latestMessage.localizedCaseInsensitiveContains(appState.searchQuery) {
                    return false
                }
            }
            switch appState.selectedFilter {
            case .all: return true
            case .direct: return !conv.isGroup
            case .groups: return conv.isGroup
            case .unread: return conv.unreadCount > 0
            }
        }
    }
}

// MARK: - Archived Row matching Screenshot 1
struct ArchivedChatRow: View {
    let archivedCount: Int

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(Color.blue.opacity(0.2))
                    .frame(width: 48, height: 48)
                Image(systemName: "archivebox.fill")
                    .foregroundColor(LiquidGlassTheme.secondaryAccent)
                    .font(.system(size: 20))
            }

            VStack(alignment: .leading, spacing: 3) {
                Text("アーカイブしたチャット")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(.white)
                Text("安全に保管された履歴")
                    .font(.system(size: 12))
                    .foregroundColor(.white.opacity(0.6))
            }

            Spacer()

            if archivedCount > 0 {
                Text("\(archivedCount)")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(Color.white.opacity(0.18)))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .liquidGlass(cornerRadius: 16)
    }
}

// MARK: - Single Conversation Row matching Screenshot 1
struct ConversationRow: View {
    let conversation: ConversationItem
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                // Avatar Circle
                ZStack {
                    Circle()
                        .fill(conversation.isGroup ? LiquidGlassTheme.primaryAccent : LiquidGlassTheme.secondaryAccent)
                        .frame(width: 50, height: 50)
                        .overlay(Circle().stroke(Color.white.opacity(0.35), lineWidth: 1))

                    Text(conversation.name.prefix(1))
                        .font(.system(size: 20, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                }

                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(conversation.name)
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(.white)
                            .lineLimit(1)

                        if conversation.screenshotBlocked {
                            Image(systemName: "lock.shield.fill")
                                .foregroundColor(Color.orange)
                                .font(.system(size: 11))
                        }

                        Spacer()

                        Text(formatTimestamp(conversation.timestamp))
                            .font(.system(size: 11.5))
                            .foregroundColor(.white.opacity(0.55))
                    }

                    HStack {
                        Text(conversation.latestMessage)
                            .font(.system(size: 12.5))
                            .foregroundColor(.white.opacity(0.72))
                            .lineLimit(1)

                        Spacer()

                        if conversation.unreadCount > 0 {
                            Text("\(conversation.unreadCount)")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 7)
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

    private func formatTimestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ja_JP")
        if Calendar.current.isDateInToday(date) {
            formatter.dateFormat = "HH:mm"
        } else {
            formatter.dateFormat = "EEE"
        }
        return formatter.string(from: date)
    }
}

