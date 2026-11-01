import Foundation
import SwiftUI
import Combine

// MARK: - Central Application State for nemix iOS

public enum HomeFilter: String, CaseIterable {
    case all = "すべて"
    case direct = "個人チャット"
    case groups = "グループ"
    case unread = "未読"
}

public enum MainTab: Int {
    case chats = 0
    case contacts = 1
    case settings = 2
    case profile = 3
}

public final class AppState: ObservableObject {
    @Published public var myNickname: String = "Haruka"
    @Published public var myUuid: String = "8f3a9c12-4b7e-4d2a-9e1f-6c8b2a5d0e3f"
    @Published public var isSetupCompleted: Bool = false
    @Published public var isRelayNodeEnabled: Bool = true // Asked during setup with Pros & Cons
    @Published public var isStealthMode: Bool = false
    @Published public var selectedTab: MainTab = .chats
    @Published public var selectedFilter: HomeFilter = .all
    @Published public var searchQuery: String = ""

    // Conversations matching Screenshots 1, 2, 3
    @Published public var conversations: [ConversationItem] = []
    @Published public var contacts: [ContactItem] = []
    @Published public var messages: [String: [ChatMessage]] = [:] // key: chatId or "groupId:channelId"

    public init() {
        loadInitialData()
    }

    private func loadInitialData() {
        // Sample Multi-channel Group matching user's Screenshot 1 & 2 ("写真共有" with "2026大阪旅行" and "General")
        let osakaTopic = P2PChannel(id: "osaka_trip", name: "2026大阪旅行", icon: "message.fill", unreadCount: 2)
        let generalTopic = P2PChannel(id: "general", name: "General", icon: "number", unreadCount: 0)

        let photoGroup = P2PGroupItem(
            id: "grp_photo_share",
            name: "写真共有",
            avatarInitial: "写",
            memberCount: 2,
            style: .channels,
            screenshotBlocked: true,
            channels: [osakaTopic, generalTopic],
            latestMessageSnippet: "2 2026大阪旅行, # General - 您: [画像] 3张图片",
            latestTimestamp: Date().addingTimeInterval(-3600),
            unreadCount: 2
        )

        conversations = [
            ConversationItem(
                id: photoGroup.id,
                name: photoGroup.name,
                isGroup: true,
                groupData: photoGroup,
                latestMessage: "2 2026大阪旅行, # General - 您: [画像] 3张图片",
                timestamp: Date().addingTimeInterval(-3600),
                unreadCount: 2,
                isArchived: false,
                screenshotBlocked: true
            ),
            ConversationItem(
                id: "peer_botfather",
                name: "BotFather",
                isGroup: false,
                latestMessage: "Done! Congratulations on your new bot...",
                timestamp: Date().addingTimeInterval(-86400 * 2),
                unreadCount: 0,
                isArchived: false,
                screenshotBlocked: false
            ),
            ConversationItem(
                id: "peer_rumi",
                name: "正宗るみさんのちんぽ...",
                isGroup: false,
                latestMessage: "海外行きたいけど今回は国内線😭",
                timestamp: Date().addingTimeInterval(-86400 * 3),
                unreadCount: 0,
                isArchived: false,
                screenshotBlocked: true
            ),
            ConversationItem(
                id: "peer_travel",
                name: "旅行写真",
                isGroup: false,
                latestMessage: "您已创建群组",
                timestamp: Date().addingTimeInterval(-86400 * 5),
                unreadCount: 0,
                isArchived: false,
                screenshotBlocked: false
            )
        ]

        contacts = [
            ContactItem(id: "ed_rumi", nickname: "正宗るみ", uuid: "4a2d-89f1-6c8b", isOnline: true),
            ContactItem(id: "ed_kenji", nickname: "Kenji", uuid: "9e1f-6c8b-2a5d", isOnline: true),
            ContactItem(id: "ed_mika", nickname: "Mika", uuid: "1a2b-3c4d-5e6f", isOnline: false)
        ]

        // Seed messages for the "General" channel in "写真共有" matching Screenshot 3
        messages["grp_photo_share:general"] = []
        messages["grp_photo_share:osaka_trip"] = [
            ChatMessage(
                senderId: "peer_friend",
                senderName: "友人",
                content: "大阪旅行の写真3枚送るね！",
                timestamp: Date().addingTimeInterval(-3600 * 2),
                isSelf: false
            ),
            ChatMessage(
                senderId: "self",
                senderName: "Haruka",
                content: "ありがとう！P2P暗号化で直接届いたよ ✨",
                timestamp: Date().addingTimeInterval(-3600),
                isSelf: true
            )
        ]
    }

    public func sendMessage(to chatKey: String, content: String) {
        let newMsg = ChatMessage(
            senderId: "self",
            senderName: myNickname,
            content: content,
            timestamp: Date(),
            isSelf: true
        )
        if messages[chatKey] == nil {
            messages[chatKey] = []
        }
        messages[chatKey]?.append(newMsg)
    }
}

