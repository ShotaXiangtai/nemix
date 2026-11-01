import Foundation

// MARK: - Core Domain Models for nemix iOS

public enum GroupStyle: String, Codable {
    case stream = "STREAM"       // Single message feed
    case channels = "CHANNELS"   // Topic / category channels
}

public struct P2PChannel: Identifiable, Codable, Equatable {
    public var id: String
    public var name: String
    public var icon: String // "message" or "tag"
    public var unreadCount: Int

    public init(id: String, name: String, icon: String = "number", unreadCount: Int = 0) {
        self.id = id
        self.name = name
        self.icon = icon
        self.unreadCount = unreadCount
    }
}

public struct P2PGroupItem: Identifiable, Codable, Equatable {
    public var id: String
    public var name: String
    public var avatarInitial: String
    public var memberCount: Int
    public var style: GroupStyle
    public var screenshotBlocked: Bool
    public var channels: [P2PChannel]
    public var latestMessageSnippet: String
    public var latestTimestamp: Date
    public var unreadCount: Int

    public init(
        id: String,
        name: String,
        avatarInitial: String? = nil,
        memberCount: Int = 2,
        style: GroupStyle = .channels,
        screenshotBlocked: Bool = false,
        channels: [P2PChannel] = [],
        latestMessageSnippet: String = "",
        latestTimestamp: Date = Date(),
        unreadCount: Int = 0
    ) {
        self.id = id
        self.name = name
        self.avatarInitial = avatarInitial ?? String(name.prefix(1))
        self.memberCount = memberCount
        self.style = style
        self.screenshotBlocked = screenshotBlocked
        self.channels = channels
        self.latestMessageSnippet = latestMessageSnippet
        self.latestTimestamp = latestTimestamp
        self.unreadCount = unreadCount
    }
}

public struct ChatMessage: Identifiable, Codable, Equatable {
    public var id: String
    public var senderId: String
    public var senderName: String
    public var content: String
    public var timestamp: Date
    public var isSelf: Bool
    public var channelId: String? // nil for 1-on-1, or topic id
    public var screenshotBlocked: Bool

    public init(
        id: String = UUID().uuidString,
        senderId: String,
        senderName: String,
        content: String,
        timestamp: Date = Date(),
        isSelf: Bool = false,
        channelId: String? = nil,
        screenshotBlocked: Bool = false
    ) {
        self.id = id
        self.senderId = senderId
        self.senderName = senderName
        self.content = content
        self.timestamp = timestamp
        self.isSelf = isSelf
        self.channelId = channelId
        self.screenshotBlocked = screenshotBlocked
    }
}

public struct ConversationItem: Identifiable, Codable, Equatable {
    public var id: String // peerId or groupId
    public var name: String
    public var isGroup: Bool
    public var groupData: P2PGroupItem?
    public var latestMessage: String
    public var timestamp: Date
    public var unreadCount: Int
    public var isArchived: Bool
    public var screenshotBlocked: Bool

    public init(
        id: String,
        name: String,
        isGroup: Bool = false,
        groupData: P2PGroupItem? = nil,
        latestMessage: String = "",
        timestamp: Date = Date(),
        unreadCount: Int = 0,
        isArchived: Bool = false,
        screenshotBlocked: Bool = false
    ) {
        self.id = id
        self.name = name
        self.isGroup = isGroup
        self.groupData = groupData
        self.latestMessage = latestMessage
        self.timestamp = timestamp
        self.unreadCount = unreadCount
        self.isArchived = isArchived
        self.screenshotBlocked = screenshotBlocked
    }
}

public struct ContactItem: Identifiable, Codable, Equatable {
    public var id: String // public key hex
    public var nickname: String
    public var uuid: String
    public var nostrPubkey: String
    public var isOnline: Bool
    public var addedAt: Date

    public init(
        id: String,
        nickname: String,
        uuid: String = "",
        nostrPubkey: String = "",
        isOnline: Bool = true,
        addedAt: Date = Date()
    ) {
        self.id = id
        self.nickname = nickname
        self.uuid = uuid
        self.nostrPubkey = nostrPubkey
        self.isOnline = isOnline
        self.addedAt = addedAt
    }
}

