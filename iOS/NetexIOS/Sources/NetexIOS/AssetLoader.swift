import Foundation

enum AssetLoader {
    static func text(_ name: String, ext: String) -> String {
        let fileName = "\(name).\(ext)"
        let candidates = [
            "NetexAssets/\(fileName)",
            "NetexAssets/vendor/\(fileName)",
            fileName
        ]

        for candidate in candidates {
            if let url = fileURL(forAssetPath: candidate),
               let text = try? String(contentsOf: url, encoding: .utf8) {
                return text
            }
        }

        return ""
    }

    static func assetURL(_ path: String) -> URL {
        URL(string: "\(NetexAssetSchemeHandler.scheme)://bundle/\(path)")!
    }

    static func fileURL(forAssetPath path: String) -> URL? {
        let clean = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let root = Bundle.main.resourceURL
        let direct = root?.appendingPathComponent(clean)
        if let direct, FileManager.default.fileExists(atPath: direct.path) { return direct }

        if clean.hasPrefix("NetexAssets/") {
            let tail = String(clean.dropFirst("NetexAssets/".count))
            let nested = root?.appendingPathComponent(tail)
            if let nested, FileManager.default.fileExists(atPath: nested.path) { return nested }
        }

        let ns = clean as NSString
        return Bundle.main.url(forResource: ns.deletingPathExtension, withExtension: ns.pathExtension)
    }
}
