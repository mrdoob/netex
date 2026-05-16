import Foundation

enum NetexURL {
    static func resolved(from input: String) -> URL? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        if let url = URL(string: trimmed), url.scheme != nil {
            return url
        }

        if looksLikeDomain(trimmed), let url = URL(string: "https://\(trimmed)") {
            return url
        }

        var components = URLComponents(string: "https://duckduckgo.com/")
        components?.queryItems = [URLQueryItem(name: "q", value: trimmed)]
        return components?.url
    }

    static func displayURL(_ url: URL?) -> String {
        guard let url else { return "" }
        if url.absoluteString == "about:blank" { return "" }
        return url.absoluteString
    }

    static func shortened(_ url: String, pageURL: String) -> String {
        let cleanPage = pageURL.split(separator: "?").first?.split(separator: "#").first.map(String.init) ?? pageURL
        if let slash = cleanPage.lastIndex(of: "/") {
            let directory = String(cleanPage[...slash])
            if url.hasPrefix(directory) {
                let suffix = String(url.dropFirst(directory.count))
                return suffix.isEmpty ? "./" : suffix
            }
        }

        guard
            let parsed = URL(string: pageURL),
            let scheme = parsed.scheme,
            let host = parsed.host
        else { return url }

        var origin = "\(scheme)://\(host)"
        if let port = parsed.port { origin += ":\(port)" }
        if url.hasPrefix(origin) {
            return String(url.dropFirst(origin.count))
        }
        return url
    }

    private static func looksLikeDomain(_ input: String) -> Bool {
        guard !input.contains(" ") else { return false }
        if input == "localhost" || input.hasPrefix("localhost:") { return true }
        return input.contains(".")
    }
}
