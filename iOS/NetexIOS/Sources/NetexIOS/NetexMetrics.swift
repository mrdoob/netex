import Foundation
import os

enum NetexMetrics {
    private static let log = OSLog(subsystem: "com.mrdoob.netex.ios", category: "performance")

    static func mark(_ name: StaticString) {
        os_signpost(.event, log: log, name: name)
    }

    static func mark(_ name: String, metadata: [String: Any] = [:]) {
        os_log("netex mark %{public}@ %{public}@", log: log, type: .debug, name, JSONBridge.jsonString(metadata))
    }
}
