import Foundation

struct ConsoleEntry {
    let level: String
    let segments: [[String: Any]]
    let source: String
    let line: Int
}

struct NetworkEntry {
    let method: String
    let url: String
    let status: Int
    let body: String
    let contentType: String
    let durationMs: Int
    let size: Int
    let requestID: String
    let pending: Bool
}

enum JSONBridge {
    static func parseObject(_ body: Any) -> [String: Any]? {
        if let object = body as? [String: Any] { return object }
        guard let string = body as? String, let data = string.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    static func quoted(_ string: String) -> String {
        guard
            let data = try? JSONSerialization.data(withJSONObject: [string]),
            let json = String(data: data, encoding: .utf8)
        else { return "\"\"" }
        return String(json.dropFirst().dropLast())
    }

    static func jsonString(_ object: Any) -> String {
        guard
            JSONSerialization.isValidJSONObject(object),
            let data = try? JSONSerialization.data(withJSONObject: object),
            let string = String(data: data, encoding: .utf8)
        else { return "{}" }
        return string
    }
}
