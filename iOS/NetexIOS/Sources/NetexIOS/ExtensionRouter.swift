import Foundation

final class ExtensionRouter {
    private struct Port {
        var id: String
        var name: String
    }

    private var panelPorts: [String: Port] = [:]

    func handlePanelEnvelope(_ envelope: BridgeEnvelope) {
        switch envelope.type {
        case .extensionPortConnect:
            let portID = envelope.payload["portId"] as? String ?? envelope.id ?? ""
            guard !portID.isEmpty else { return }
            panelPorts[portID] = Port(id: portID, name: envelope.payload["name"] as? String ?? "")
        case .extensionPortDisconnect:
            let portID = envelope.payload["portId"] as? String ?? envelope.id ?? ""
            panelPorts.removeValue(forKey: portID)
        default:
            break
        }
    }

    func replayForPageReady() -> [BridgeEnvelope] {
        panelPorts.values
            .sorted { $0.id < $1.id }
            .map { .extensionPortConnect(portID: $0.id, name: $0.name) }
    }

    func resetPageState() -> BridgeEnvelope {
        BridgeEnvelope(type: .webNavigationCommitted, source: "native", payload: ["frameId": 0])
    }
}

final class BlobStore {
    private let limit: Int
    private var order: [String] = []
    private var values: [String: String] = [:]

    init(limit: Int = 64) {
        self.limit = max(1, limit)
    }

    func insert(_ key: String, dataURL: String) {
        if values[key] == nil { order.append(key) }
        values[key] = dataURL
        while order.count > limit {
            let oldest = order.removeFirst()
            values.removeValue(forKey: oldest)
        }
    }

    func value(for key: String) -> String? {
        values[key]
    }
}
