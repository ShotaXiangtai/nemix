import Foundation
import SwiftUI
import CoreImage.CIFilterBuiltins

// MARK: - iOS Contact Exchange Manager (Permanent Revocable & Time-Limited QR / URL)

public enum InviteType: String, Codable {
    case permanent = "perm"
    case expiring = "exp"

    // Also accept the older Android values "PERMANENT" / "EXPIRING".
    public init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        switch raw.lowercased() {
        case "perm", "permanent": self = .permanent
        case "exp", "expiring": self = .expiring
        default:
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Unknown invite type \(raw)"))
        }
    }
}

public struct ContactInvitePayload: Codable {
    public var version: Int = 1
    public var nickname: String
    public var nostrPubkeyHex: String
    public var edPublicKeyHex: String
    public var uuid: String
    public var type: InviteType
    public var createdAtMs: Int64
    public var expiresAtMs: Int64 // 0 for permanent
    public var salt: String
}

public enum ValidationResult {
    case valid(ContactInvitePayload)
    case expired(Date)
    case revoked(String)
    case error(String)
}

public final class ContactExchangeManager: ObservableObject {
    public static let shared = ContactExchangeManager()

    public static let schemeNemix = "nemix"
    public static let hostContact = "contact"
    public static let webPrefix = "https://nemi-x.com/c#"

    private let userDefaultsKeySalt = "nemix_ios_permanent_salt"
    @Published public private(set) var permanentSalt: String = ""

    private let qrContext = CIContext()
    private let qrFilter = CIFilter.qrCodeGenerator()

    private init() {
        if let stored = UserDefaults.standard.string(forKey: userDefaultsKeySalt), !stored.isEmpty {
            self.permanentSalt = stored
        } else {
            let newSalt = generateRandomSalt()
            UserDefaults.standard.set(newSalt, forKey: userDefaultsKeySalt)
            self.permanentSalt = newSalt
        }
    }

    private func generateRandomSalt() -> String {
        var bytes = [UInt8](repeating: 0, count: 8)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Reissues (regenerates) the permanent invite salt, instantly revoking all previously shared permanent URLs/QRs.
    @discardableResult
    public func regeneratePermanentInvite() -> String {
        let newSalt = generateRandomSalt()
        UserDefaults.standard.set(newSalt, forKey: userDefaultsKeySalt)
        self.permanentSalt = newSalt
        return newSalt
    }

    /// Generates a new ContactInvitePayload for the local user.
    public func createInvite(
        nickname: String,
        nostrPubkey: String,
        edPubkey: String,
        uuid: String,
        type: InviteType,
        durationSeconds: TimeInterval = 3600
    ) -> ContactInvitePayload {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let expMs: Int64
        let salt: String

        switch type {
        case .permanent:
            expMs = 0
            salt = permanentSalt
        case .expiring:
            expMs = nowMs + Int64(durationSeconds * 1000)
            salt = generateRandomSalt()
        }

        return ContactInvitePayload(
            version: 1,
            nickname: nickname,
            nostrPubkeyHex: nostrPubkey,
            edPublicKeyHex: edPubkey,
            uuid: uuid,
            type: type,
            createdAtMs: nowMs,
            expiresAtMs: expMs,
            salt: salt
        )
    }

    public func payloadToNemixUri(_ payload: ContactInvitePayload) -> String {
        var components = URLComponents()
        components.scheme = Self.schemeNemix
        components.host = Self.hostContact
        components.path = "/add"
        components.queryItems = [
            URLQueryItem(name: "v", value: String(payload.version)),
            URLQueryItem(name: "name", value: payload.nickname),
            URLQueryItem(name: "pk", value: payload.nostrPubkeyHex),
            URLQueryItem(name: "ed", value: payload.edPublicKeyHex),
            URLQueryItem(name: "uuid", value: payload.uuid),
            URLQueryItem(name: "type", value: payload.type.rawValue),
            URLQueryItem(name: "ts", value: String(payload.createdAtMs)),
            URLQueryItem(name: "exp", value: String(payload.expiresAtMs)),
            URLQueryItem(name: "salt", value: payload.salt)
        ]
        return components.url?.absoluteString ?? ""
    }

    public func payloadToWebUrl(_ payload: ContactInvitePayload) -> String {
        guard let jsonData = try? JSONEncoder().encode(payload) else { return "" }
        let b64 = jsonData.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        return "\(Self.webPrefix)\(b64)"
    }

    /// Validates an incoming deep link or scanned text.
    public func validateAndParse(_ raw: String) -> ValidationResult {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .error("コードまたはURLが空です") }

        do {
            if trimmed.contains("#") {
                let fragment = trimmed.components(separatedBy: "#").last ?? ""
                var b64 = fragment.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
                while b64.count % 4 != 0 { b64.append("=") }
                if let data = Data(base64Encoded: b64),
                   let payload = try? JSONDecoder().decode(ContactInvitePayload.self, from: data) {
                    return checkValidity(payload)
                }
            }

            if let url = URL(string: trimmed), url.scheme?.lowercased() == Self.schemeNemix {
                let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
                let query = components?.queryItems ?? []
                let name = query.first(where: { $0.name == "name" })?.value ?? "nemix-user"
                let pk = query.first(where: { $0.name == "pk" })?.value ?? ""
                let ed = query.first(where: { $0.name == "ed" })?.value ?? ""
                let uuid = query.first(where: { $0.name == "uuid" })?.value ?? ""
                let typeStr = query.first(where: { $0.name == "type" })?.value ?? "perm"
                let ts = Int64(query.first(where: { $0.name == "ts" })?.value ?? "0") ?? 0
                let exp = Int64(query.first(where: { $0.name == "exp" })?.value ?? "0") ?? 0
                let salt = query.first(where: { $0.name == "salt" })?.value ?? ""

                let payload = ContactInvitePayload(
                    version: 1,
                    nickname: name,
                    nostrPubkeyHex: pk,
                    edPublicKeyHex: ed,
                    uuid: uuid,
                    type: typeStr == "exp" ? .expiring : .permanent,
                    createdAtMs: ts,
                    expiresAtMs: exp,
                    salt: salt
                )
                return checkValidity(payload)
            }

            return .error("nemix の招待形式ではありません")
        }
    }

    private func checkValidity(_ payload: ContactInvitePayload) -> ValidationResult {
        if payload.type == .expiring && payload.expiresAtMs > 0 {
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            if nowMs > payload.expiresAtMs {
                return .expired(Date(timeIntervalSince1970: Double(payload.expiresAtMs) / 1000.0))
            }
        }
        return .valid(payload)
    }

    /// Generates a sharp UIImage QR code using CoreImage with nearest-neighbor scaling.
    public func generateQRCodeImage(from string: String, targetSize: CGFloat = 400) -> UIImage? {
        guard let data = string.data(using: .utf8) else { return nil }
        qrFilter.setValue(data, forKey: "inputMessage")
        qrFilter.setValue("M", forKey: "inputCorrectionLevel")

        guard let outputImage = qrFilter.outputImage else { return nil }
        let scale = targetSize / outputImage.extent.size.width
        let transformed = outputImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        if let cgImage = qrContext.createCGImage(transformed, from: transformed.extent) {
            return UIImage(cgImage: cgImage)
        }
        return nil
    }
}

