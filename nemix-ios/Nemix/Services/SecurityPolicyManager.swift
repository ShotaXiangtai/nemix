import Foundation
import UIKit
import Combine

// MARK: - iOS Security Policy Manager: Mutual Screenshot Blocking & Synchronized Deletion

public final class SecurityPolicyManager: ObservableObject {
    public static let shared = SecurityPolicyManager()

    @Published public var isScreenCaptured: Bool = UIScreen.main.isCaptured
    @Published public var screenshotBlockedChats: Set<String> = [] // peerId or groupId

    private var cancellables = Set<AnyCancellable>()

    private init() {
        NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.isScreenCaptured = UIScreen.main.isCaptured
                }
            }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: UIApplication.userDidTakeScreenshotNotification)
            .sink { [weak self] _ in
                // Alert if a screenshot was taken in a blocked chat
                print("[SecurityPolicy] Screenshot detected on iOS device")
            }
            .store(in: &cancellables)
    }

    public func isScreenshotBlocked(for chatId: String) -> Bool {
        return screenshotBlockedChats.contains(chatId)
    }

    public func toggleScreenshotBlocked(for chatId: String) -> Bool {
        if screenshotBlockedChats.contains(chatId) {
            screenshotBlockedChats.remove(chatId)
            return false
        } else {
            screenshotBlockedChats.insert(chatId)
            return true
        }
    }
}

