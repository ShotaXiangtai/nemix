import SwiftUI

// MARK: - nemix iOS Application Entry Point

@main
struct NemixApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            MainView()
                .environmentObject(appState)
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    handleIncomingDeepLink(url)
                }
        }
    }

    private func handleIncomingDeepLink(_ url: URL) {
        guard url.scheme?.lowercased() == ContactExchangeManager.schemeNemix else { return }
        switch ContactExchangeManager.shared.validateAndParse(url.absoluteString) {
        case .valid(let payload):
            let newContact = ContactItem(
                id: payload.edPublicKeyHex.isEmpty ? payload.nostrPubkeyHex : payload.edPublicKeyHex,
                nickname: payload.nickname,
                uuid: payload.uuid,
                nostrPubkey: payload.nostrPubkeyHex,
                isOnline: true
            )
            if !appState.contacts.contains(where: { $0.id == newContact.id }) {
                appState.contacts.append(newContact)
            }
            appState.selectedTab = .contacts
        default:
            break
        }
    }
}

