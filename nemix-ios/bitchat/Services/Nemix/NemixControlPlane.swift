//
// NemixControlPlane.swift
// nemix
//
// The nemix Android app carries its extensions (profile pictures, groups, calls, media
// chunks, policy sync, …) as private text messages of the form "[NEMIX_<KIND>:<json>]".
// They are control frames, never chat text: this file keeps them out of the timeline and
// handles the ones iOS understands so far (profile name + picture).
//

import Foundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

enum NemixControlPlane {
    static let profilePrefix = "[NEMIX_PROFILE:"

    static func isControlPacket(_ content: String) -> Bool {
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.hasPrefix("[NEMIX_") && trimmed.hasSuffix("]")
    }

    /// Returns `true` when `content` is a nemix control frame: it has been handled (or is a kind
    /// iOS does not support yet) and must not be stored or shown as a chat message.
    @MainActor
    static func intercept(_ content: String, senderPeerID: PeerID?, senderNickname: String) -> Bool {
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isControlPacket(trimmed) else { return false }
        if trimmed.hasPrefix(profilePrefix) {
            NemixPeerAvatarStore.shared.handleProfilePacket(
                trimmed,
                senderPeerID: senderPeerID,
                fallbackName: senderNickname
            )
        }
        return true
    }
}

/// Profile pictures received from nemix peers, kept in memory and on disk (Application Support).
@MainActor
final class NemixPeerAvatarStore: ObservableObject {
    static let shared = NemixPeerAvatarStore()

    /// Bumped on every change so SwiftUI views observing the store refresh.
    @Published private(set) var revision = 0

    private var cache: [String: Data] = [:]
    private let maxAvatarBytes = 32_768 // same cap as the Android sender

    private lazy var directory: URL? = {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return nil
        }
        let dir = base.appendingPathComponent("nemix-avatars", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    func handleProfilePacket(_ packet: String, senderPeerID: PeerID?, fallbackName: String) {
        let json = String(packet.dropFirst(NemixControlPlane.profilePrefix.count).dropLast())
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return }

        let name = (object["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? fallbackName
        var keys: [String] = []
        if let peer = senderPeerID, !peer.id.isEmpty { keys.append(Self.key(for: peer)) }
        if !name.isEmpty { keys.append(Self.nameKey(name)) }
        guard !keys.isEmpty else { return }

        let avatarB64 = object["avatar"] as? String ?? ""
        if avatarB64.isEmpty {
            keys.forEach(remove)
        } else if let bytes = Data(base64Encoded: avatarB64, options: .ignoreUnknownCharacters),
                  bytes.count <= maxAvatarBytes,
                  Self.isDecodableImage(bytes) {
            keys.forEach { store(bytes, for: $0) }
        }
        revision &+= 1
    }

    #if canImport(UIKit)
    /// Picture for a conversation: by peer ID first, then by the peer's display name.
    func image(for peerID: PeerID?, name: String?) -> UIImage? {
        if let peerID, let data = data(for: Self.key(for: peerID)) { return UIImage(data: data) }
        if let name, !name.isEmpty, let data = data(for: Self.nameKey(name)) { return UIImage(data: data) }
        return nil
    }
    #endif

    // MARK: - Storage

    private static func key(for peer: PeerID) -> String { "peer:" + peer.id.lowercased() }
    private static func nameKey(_ name: String) -> String { "name:" + name }

    private static func isDecodableImage(_ data: Data) -> Bool {
        #if canImport(UIKit)
        return UIImage(data: data) != nil
        #else
        return true
        #endif
    }

    private func fileURL(for key: String) -> URL? {
        let safe = Data(key.utf8).base64EncodedString()
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "=", with: "")
        return directory?.appendingPathComponent(safe + ".img")
    }

    private func data(for key: String) -> Data? {
        if let cached = cache[key] { return cached }
        guard let url = fileURL(for: key), let data = try? Data(contentsOf: url) else { return nil }
        cache[key] = data
        return data
    }

    private func store(_ data: Data, for key: String) {
        cache[key] = data
        if let url = fileURL(for: key) {
            try? data.write(to: url, options: [.atomic, .completeFileProtection])
        }
    }

    private func remove(_ key: String) {
        cache[key] = nil
        if let url = fileURL(for: key) { try? FileManager.default.removeItem(at: url) }
    }
}

#if canImport(UIKit)
/// Small round profile picture; renders nothing when the peer has not shared one.
struct NemixAvatarView: View {
    let peerID: PeerID?
    let name: String?
    var size: CGFloat = 26

    @ObservedObject private var store = NemixPeerAvatarStore.shared

    var body: some View {
        if let image = store.image(for: peerID, name: name) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: size, height: size)
                .clipShape(Circle())
                .accessibilityHidden(true)
        }
    }
}
#endif
