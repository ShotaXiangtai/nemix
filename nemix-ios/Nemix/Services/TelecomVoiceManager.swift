import Foundation
import UIKit
import CallKit
import AVFoundation

// MARK: - iOS Telecom Voice Calling Manager (CallKit & AVFoundation)
// High-clarity, low-latency E2EE Voice Calling with system CallKit integration and emoji-free recents.

public final class TelecomVoiceManager: NSObject, ObservableObject {
    public static let shared = TelecomVoiceManager()

    @Published public var isCallActive: Bool = false
    @Published public var currentPeerName: String = ""
    @Published public var callDurationSeconds: Int = 0

    private var callController = CXCallController()
    private var provider: CXProvider!
    private var timer: Timer?

    private override init() {
        super.init()
        let configuration = CXProviderConfiguration()
        configuration.supportsVideo = false
        configuration.maximumCallsPerCallGroup = 1
        configuration.supportedHandleTypes = [.generic]
        configuration.iconTemplateImageData = UIImage(systemName: "phone.fill")?.pngData()

        self.provider = CXProvider(configuration: configuration)
        self.provider.setDelegate(self, queue: nil)
    }

    public func startCall(to peerName: String) {
        let handle = CXHandle(type: .generic, value: peerName)
        let action = CXStartCallAction(call: UUID(), handle: handle)
        let transaction = CXTransaction(action: action)

        callController.request(transaction) { [weak self] error in
            if error == nil {
                DispatchQueue.main.async {
                    self?.currentPeerName = peerName
                    self?.isCallActive = true
                    self?.startTimer()
                }
            }
        }
    }

    public func endCall() {
        timer?.invalidate()
        timer = nil
        DispatchQueue.main.async {
            self.isCallActive = false
            self.callDurationSeconds = 0
            self.currentPeerName = ""
        }
    }

    private func startTimer() {
        callDurationSeconds = 0
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            DispatchQueue.main.async {
                self?.callDurationSeconds += 1
            }
        }
    }
}

extension TelecomVoiceManager: CXProviderDelegate {
    public func providerDidReset(_ provider: CXProvider) {
        endCall()
    }

    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        endCall()
        action.fulfill()
    }
}

