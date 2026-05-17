import Foundation

enum BridgeEnvelopeType: String, Equatable {
    case consoleEntry = "console-entry"
    case consoleBatch = "console.batch"
    case networkRecord = "network-record"
    case networkBatch = "network.batch"
    case panelEval = "panel-eval"
    case fetchBlob = "fetch-blob"
    case blobResponse = "blob-response"
    case inspectorResize = "inspector.resize"
    case perfMark = "perf.mark"
    case pageReady = "page-ready"
    case extensionPortConnect = "port-connect"
    case extensionPortMessage = "port-message"
    case extensionPortDisconnect = "port-disconnect"
    case webNavigationCommitted = "webNavigation.committed"
    case tabRemoved = "tab.removed"
    case actionSetBadgeText = "action.setBadgeText"
    case actionSetBadgeBackgroundColor = "action.setBadgeBackgroundColor"
    case actionSetIcon = "action.setIcon"
    case unknown

    init(_ raw: String) {
        switch raw {
        case "console.entry": self = .consoleEntry
        case "eval": self = .panelEval
        default: self = BridgeEnvelopeType(rawValue: raw) ?? .unknown
        }
    }
}

struct BridgeEnvelope: Equatable {
    let type: BridgeEnvelopeType
    let id: String?
    let source: String
    let timestamp: TimeInterval
    let payload: [String: Any]

    init(type: BridgeEnvelopeType, id: String? = nil, source: String = "native", timestamp: TimeInterval = Date().timeIntervalSince1970, payload: [String: Any] = [:]) {
        self.type = type
        self.id = id
        self.source = source
        self.timestamp = timestamp
        self.payload = payload
    }

    init?(_ body: Any, fallbackType: BridgeEnvelopeType? = nil, fallbackSource: String = "page") {
        guard let object = JSONBridge.parseObject(body) else { return nil }
        let rawType = object["type"] as? String
        let type = rawType.map(BridgeEnvelopeType.init) ?? fallbackType ?? .unknown
        let payload = object["payload"] as? [String: Any] ?? object
        self.init(
            type: type,
            id: object["id"] as? String ?? object["evalId"] as? String ?? object["requestId"] as? String,
            source: object["source"] as? String ?? fallbackSource,
            timestamp: object["timestamp"] as? TimeInterval ?? Date().timeIntervalSince1970,
            payload: payload
        )
    }

    static func extensionPortConnect(portID: String, name: String) -> BridgeEnvelope {
        BridgeEnvelope(type: .extensionPortConnect, source: "panel", payload: ["portId": portID, "name": name])
    }

    func jsonString() -> String {
        var object: [String: Any] = [
            "type": type.rawValue,
            "source": source,
            "timestamp": timestamp,
            "payload": payload
        ]
        if let id { object["id"] = id }
        payload.forEach { object[$0.key] = $0.value }
        return JSONBridge.jsonString(object)
    }

    static func == (lhs: BridgeEnvelope, rhs: BridgeEnvelope) -> Bool {
        lhs.type == rhs.type &&
        lhs.id == rhs.id &&
        lhs.source == rhs.source &&
        lhs.timestamp == rhs.timestamp &&
        NSDictionary(dictionary: lhs.payload).isEqual(to: rhs.payload)
    }
}
