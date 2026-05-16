import Foundation

struct NetexNavigationPolicy {
    enum Decision: Equatable {
        case allow
        case block
    }

    private let allowedMainFrameHosts = Set([
        "threejs.org",
        "www.threejs.org"
    ])

    func decision(for url: URL?, targetIsMainFrame: Bool) -> Decision {
        guard targetIsMainFrame else { return .allow }
        guard let url, let scheme = url.scheme?.lowercased() else { return .allow }

        if scheme == NetexAssetSchemeHandler.scheme || scheme == "about" {
            return .allow
        }

        if scheme == "http" || scheme == "https" {
            guard let host = url.host?.lowercased() else { return .block }
            return allowedMainFrameHosts.contains(host) ? .allow : .block
        }

        return .block
    }
}
