//
// BackgroundInboxRefresh.swift
// nemix
//
// Receives internet (Nostr) messages while the app is in the background without any
// nemix server: iOS background app refresh briefly wakes the app, it connects to the
// user's Nostr relays, existing handlers post local notifications for new messages,
// and the connection is closed again. iOS decides how often this runs.
//

#if os(iOS)
import BackgroundTasks
import UIKit

enum BackgroundInboxRefresh {
    static let taskIdentifier = "im.nemix.ios.inbox-refresh"
    private static let listenNanoseconds: UInt64 = 25 * 1_000_000_000

    /// Must be called before the app finishes launching.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(refresh)
        }
    }

    /// Asks iOS for the next background check (iOS may run it later than requested).
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    private final class Completion {
        private var done = false
        func finish(_ task: BGTask, success: Bool) {
            guard !done else { return }
            done = true
            task.setTaskCompleted(success: success)
        }
    }

    private static func handle(_ task: BGAppRefreshTask) {
        schedule()
        let completion = Completion()

        let work = Task { @MainActor in
            if UIApplication.shared.applicationState == .active {
                completion.finish(task, success: true)
                return
            }
            NostrRelayManager.shared.connect()
            try? await Task.sleep(nanoseconds: listenNanoseconds)
            if UIApplication.shared.applicationState != .active {
                NostrRelayManager.shared.disconnect()
            }
            completion.finish(task, success: true)
        }

        task.expirationHandler = {
            work.cancel()
            Task { @MainActor in
                if UIApplication.shared.applicationState != .active {
                    NostrRelayManager.shared.disconnect()
                }
                completion.finish(task, success: false)
            }
        }
    }
}
#endif
