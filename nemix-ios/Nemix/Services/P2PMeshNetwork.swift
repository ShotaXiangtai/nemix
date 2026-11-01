import Foundation
import UIKit
import CoreBluetooth
import MultipeerConnectivity

// MARK: - iOS P2P Mesh Network: CoreBluetooth & MultipeerConnectivity
// 100% Serverless local peer-to-peer communication without cellular or Wi-Fi router.

public final class P2PMeshNetwork: NSObject, ObservableObject {
    public static let shared = P2PMeshNetwork()

    public static let serviceUUID = CBUUID(string: "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
    public static let multipeerServiceType = "nemix-p2p"

    @Published public var connectedPeerCount: Int = 0
    @Published public var nearbyPeers: [String] = [] // Peer IDs
    @Published public var isMeshActive: Bool = true
    @Published public var isStealthMode: Bool = false

    // CoreBluetooth
    private var centralManager: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!

    // MultipeerConnectivity
    private var peerId: MCPeerID!
    private var session: MCSession!
    private var advertiser: MCNearbyServiceAdvertiser!
    private var browser: MCNearbyServiceBrowser!

    public var onMessageReceived: ((Data, String) -> Void)?

    private override init() {
        super.init()
        let deviceName = UIDevice.current.name.prefix(15)
        self.peerId = MCPeerID(displayName: "\(deviceName)-\(UUID().uuidString.prefix(4))")
        self.session = MCSession(peer: peerId, securityIdentity: nil, encryptionPreference: .required)
        self.session.delegate = self

        self.advertiser = MCNearbyServiceAdvertiser(
            peer: peerId,
            discoveryInfo: ["v": "1", "app": "nemix"],
            serviceType: Self.multipeerServiceType
        )
        self.advertiser.delegate = self

        self.browser = MCNearbyServiceBrowser(peer: peerId, serviceType: Self.multipeerServiceType)
        self.browser.delegate = self

        self.centralManager = CBCentralManager(delegate: self, queue: nil)
        self.peripheralManager = CBPeripheralManager(delegate: self, queue: nil)

        startAdvertisingAndBrowsing()
    }

    public func setStealthMode(_ enabled: Bool) {
        self.isStealthMode = enabled
        if enabled {
            advertiser.stopAdvertisingPeer()
            browser.stopBrowsingForPeers()
            if peripheralManager.isAdvertising { peripheralManager.stopAdvertising() }
            if centralManager.isScanning { centralManager.stopScan() }
        } else {
            startAdvertisingAndBrowsing()
        }
    }

    private func startAdvertisingAndBrowsing() {
        guard !isStealthMode else { return }
        advertiser.startAdvertisingPeer()
        browser.startBrowsingForPeers()
    }

    public func broadcastPacket(_ data: Data) {
        guard !session.connectedPeers.isEmpty else { return }
        try? session.send(data, toPeers: session.connectedPeers, with: .reliable)
    }

    public func sendPacket(_ data: Data, to peerIdString: String) {
        let targets = session.connectedPeers.filter { $0.displayName.contains(peerIdString) }
        guard !targets.isEmpty else { return }
        try? session.send(data, toPeers: targets, with: .reliable)
    }
}

// MARK: - MCSessionDelegate
extension P2PMeshNetwork: MCSessionDelegate {
    public func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        DispatchQueue.main.async {
            self.connectedPeerCount = session.connectedPeers.count
            self.nearbyPeers = session.connectedPeers.map { $0.displayName }
        }
    }

    public func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        DispatchQueue.main.async {
            self.onMessageReceived?(data, peerID.displayName)
        }
    }

    public func session(_ session: MCSession, didReceive stream: InputStream, withName streamName: String, fromPeer peerID: MCPeerID) {}
    public func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, with progress: Progress) {}
    public func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}

// MARK: - MCNearbyServiceAdvertiserDelegate & BrowserDelegate
extension P2PMeshNetwork: MCNearbyServiceAdvertiserDelegate, MCNearbyServiceBrowserDelegate {
    public func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer peerID: MCPeerID, withContext context: Data?, invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        invitationHandler(!isStealthMode, self.session)
    }

    public func browser(_ browser: MCNearbyServiceBrowser, foundPeer peerID: MCPeerID, withDiscoveryInfo info: [String: String]?) {
        guard !isStealthMode else { return }
        browser.invitePeer(peerID, to: self.session, withContext: nil, timeout: 15)
    }

    public func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {}
}

// MARK: - CBCentralManagerDelegate & CBPeripheralManagerDelegate
extension P2PMeshNetwork: CBCentralManagerDelegate, CBPeripheralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn && !isStealthMode {
            central.scanForPeripherals(withServices: [Self.serviceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        }
    }

    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn && !isStealthMode {
            let adData: [String: Any] = [
                CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID],
                CBAdvertisementDataLocalNameKey: "nemix"
            ]
            peripheral.startAdvertising(adData)
        }
    }
}

