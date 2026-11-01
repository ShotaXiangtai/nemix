import SwiftUI

// MARK: - iOS Main Navigation Container with Floating Liquid Glass Bottom Bar
// 4 Bottom Tabs matching User Screenshots 1 & 2:
// - 1. Chats (聊天)
// - 2. Contacts (联系人)
// - 3. Settings (设置)
// - 4. Profile (个人资料)
// - On first launch, presents SetupRelaySheet asking about P2P Relay Node (with Pros & Cons)

public struct MainView: View {
    @EnvironmentObject var appState: AppState
    @State private var showSetupSheet = false

    public init() {}

    public var body: some View {
        ZStack(alignment: .bottom) {
            // Main Tab Content
            Group {
                switch appState.selectedTab {
                case .chats:
                    ChatsListView()
                case .contacts:
                    ContactsView()
                case .settings:
                    SettingsView()
                case .profile:
                    ProfileView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            // Floating Liquid Glass Bottom Tab Bar (matching Screenshots 1 & 2)
            HStack(spacing: 0) {
                tabItem(icon: "message.fill", title: "チャット", tab: .chats)
                tabItem(icon: "person.2.fill", title: "連絡先", tab: .contacts)
                tabItem(icon: "gearshape.fill", title: "設定", tab: .settings)
                tabItem(icon: "person.crop.circle.fill", title: "プロフィール", tab: .profile)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .liquidGlass(cornerRadius: 28, shadowRadius: 18)
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
        }
        .onAppear {
            if !appState.isSetupCompleted {
                showSetupSheet = true
            }
        }
        .sheet(isPresented: $showSetupSheet) {
            SetupRelaySheet(isPresented: $showSetupSheet)
        }
    }

    private func tabItem(icon: String, title: String, tab: MainTab) -> some View {
        let isSelected = appState.selectedTab == tab
        return Button(action: {
            let generator = UISelectionFeedbackGenerator()
            generator.selectionChanged()
            appState.selectedTab = tab
        }) {
            VStack(spacing: 4) {
                ZStack {
                    if isSelected {
                        Capsule()
                            .fill(LiquidGlassTheme.primaryAccent.opacity(0.22))
                            .frame(width: 54, height: 30)
                    }
                    Image(systemName: icon)
                        .font(.system(size: 18, weight: isSelected ? .bold : .medium))
                        .foregroundColor(isSelected ? LiquidGlassTheme.primaryAccent : .white.opacity(0.55))
                }
                Text(title)
                    .font(.system(size: 10, weight: isSelected ? .bold : .medium))
                    .foregroundColor(isSelected ? .white : .white.opacity(0.55))
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }
}

