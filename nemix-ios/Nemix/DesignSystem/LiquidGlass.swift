import SwiftUI

// MARK: - Liquid Glass Design System for nemix iOS
// Pure Apple-native Liquid Glass aesthetics:
// - Refractive translucent ultraThinMaterial layers
// - Specular border highlights with dual-layer angular light reflection
// - Fluid mesh gradient backdrops
// - Floating frosted glass components

public struct LiquidGlassTheme {
    public static let primaryAccent = Color(red: 0.11, green: 0.72, blue: 0.55) // nemix Teal/Emerald
    public static let secondaryAccent = Color(red: 0.28, green: 0.55, blue: 0.98) // nemix Electric Blue
    public static let vibrantPurple = Color(red: 0.55, green: 0.32, blue: 0.95)
    public static let glassWhite = Color.white.opacity(0.12)
    public static let specularBorderLight = Color.white.opacity(0.65)
    public static let specularBorderDark = Color.white.opacity(0.15)
    public static let darkSurface = Color(red: 0.06, green: 0.09, blue: 0.16)
}

// MARK: - Fluid Mesh Gradient Backdrop
public struct LiquidMeshBackground: View {
    @State private var animateGradients = false

    public init() {}

    public var body: some View {
        ZStack {
            Color(red: 0.05, green: 0.07, blue: 0.12)
                .ignoresSafeArea()

            // Fluid glowing orb 1 (Teal/Emerald)
            Circle()
                .fill(
                    RadialGradient(
                        colors: [
                            LiquidGlassTheme.primaryAccent.opacity(0.40),
                            LiquidGlassTheme.primaryAccent.opacity(0.0)
                        ],
                        center: .center,
                        startRadius: 10,
                        endRadius: 260
                    )
                )
                .frame(width: 420, height: 420)
                .offset(
                    x: animateGradients ? -90 : 110,
                    y: animateGradients ? -160 : -40
                )
                .blur(radius: 65)

            // Fluid glowing orb 2 (Electric Blue)
            Circle()
                .fill(
                    RadialGradient(
                        colors: [
                            LiquidGlassTheme.secondaryAccent.opacity(0.35),
                            LiquidGlassTheme.secondaryAccent.opacity(0.0)
                        ],
                        center: .center,
                        startRadius: 10,
                        endRadius: 280
                    )
                )
                .frame(width: 440, height: 440)
                .offset(
                    x: animateGradients ? 120 : -80,
                    y: animateGradients ? 140 : 260
                )
                .blur(radius: 70)

            // Fluid glowing orb 3 (Violet shimmer)
            Circle()
                .fill(
                    RadialGradient(
                        colors: [
                            LiquidGlassTheme.vibrantPurple.opacity(0.28),
                            Color.clear
                        ],
                        center: .center,
                        startRadius: 10,
                        endRadius: 220
                    )
                )
                .frame(width: 360, height: 360)
                .offset(
                    x: animateGradients ? 40 : -50,
                    y: animateGradients ? 40 : -180
                )
                .blur(radius: 60)
        }
        .onAppear {
            withAnimation(
                .easeInOut(duration: 9.0)
                .repeatForever(autoreverses: true)
            ) {
                animateGradients.toggle()
            }
        }
    }
}

// MARK: - Liquid Glass Card Modifier
public struct LiquidGlassCardModifier: ViewModifier {
    var cornerRadius: CGFloat = 22
    var shadowRadius: CGFloat = 16

    public func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .overlay(
                        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                            .stroke(
                                LinearGradient(
                                    colors: [
                                        LiquidGlassTheme.specularBorderLight,
                                        LiquidGlassTheme.specularBorderDark,
                                        LiquidGlassTheme.specularBorderLight.opacity(0.3)
                                    ],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 1.2
                            )
                    )
                    .shadow(color: Color.black.opacity(0.22), radius: shadowRadius, x: 0, y: 8)
            )
    }
}

public extension View {
    func liquidGlass(cornerRadius: CGFloat = 22, shadowRadius: CGFloat = 16) -> some View {
        modifier(LiquidGlassCardModifier(cornerRadius: cornerRadius, shadowRadius: shadowRadius))
    }
}

// MARK: - Liquid Glass Card View Container
public struct LiquidGlassCard<Content: View>: View {
    var cornerRadius: CGFloat = 22
    @ViewBuilder var content: () -> Content

    public init(cornerRadius: CGFloat = 22, @ViewBuilder content: @escaping () -> Content) {
        self.cornerRadius = cornerRadius
        self.content = content
    }

    public var body: some View {
        content()
            .padding(18)
            .liquidGlass(cornerRadius: cornerRadius)
    }
}

// MARK: - Liquid Glass Pill Button
public struct LiquidGlassButton: View {
    let title: String
    let icon: String?
    let accent: Color
    let action: () -> Void

    public init(
        title: String,
        icon: String? = nil,
        accent: Color = LiquidGlassTheme.primaryAccent,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.icon = icon
        self.accent = accent
        self.action = action
    }

    public var body: some View {
        Button(action: {
            let generator = UIImpactFeedbackGenerator(style: .medium)
            generator.impactOccurred()
            action()
        }) {
            HStack(spacing: 8) {
                if let icon = icon {
                    Image(systemName: icon)
                        .font(.system(size: 14, weight: .semibold))
                }
                Text(title)
                    .font(.system(size: 14, weight: .bold, design: .rounded))
            }
            .foregroundColor(.white)
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .background(
                Capsule()
                    .fill(accent.opacity(0.85))
                    .overlay(
                        Capsule()
                            .stroke(Color.white.opacity(0.55), lineWidth: 1.0)
                    )
                    .shadow(color: accent.opacity(0.4), radius: 10, x: 0, y: 4)
            )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Liquid Glass Status Pill / Badge
public struct LiquidGlassBadge: View {
    let text: String
    let icon: String?
    let tint: Color

    public init(text: String, icon: String? = nil, tint: Color = LiquidGlassTheme.primaryAccent) {
        self.text = text
        self.icon = icon
        self.tint = tint
    }

    public var body: some View {
        HStack(spacing: 5) {
            if let icon = icon {
                Image(systemName: icon)
                    .font(.system(size: 10, weight: .bold))
            }
            Text(text)
                .font(.system(size: 11, weight: .bold, design: .rounded))
        }
        .foregroundColor(tint)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(
            Capsule()
                .fill(tint.opacity(0.18))
                .overlay(
                    Capsule()
                        .stroke(tint.opacity(0.45), lineWidth: 0.8)
                )
        )
    }
}

// MARK: - Liquid Glass Search Bar
public struct LiquidGlassSearchBar: View {
    @Binding var text: String
    var placeholder: String = "チャットやメッセージを検索"

    public init(text: Binding<String>, placeholder: String = "チャットやメッセージを検索") {
        self._text = text
        self.placeholder = placeholder
    }

    public var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundColor(.white.opacity(0.6))
                .font(.system(size: 15, weight: .medium))

            TextField(placeholder, text: $text)
                .foregroundColor(.white)
                .font(.system(size: 14, weight: .regular))

            if !text.isEmpty {
                Button(action: { text = "" }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.white.opacity(0.5))
                        .font(.system(size: 14))
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .liquidGlass(cornerRadius: 16, shadowRadius: 8)
    }
}

