import Foundation

enum AssetLoader {
    static func text(_ name: String, ext: String) -> String {
        let url = Bundle.main.url(forResource: name, withExtension: ext, subdirectory: "NetexAssets")
            ?? Bundle.main.url(forResource: name, withExtension: ext)
        guard let url, let text = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return text
    }
}
