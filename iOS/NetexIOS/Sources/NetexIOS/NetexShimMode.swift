import Foundation

enum NetexShimMode: Equatable {
    case off
    case consoleOnly
    case networkOnly
    case full

    init(rawValue: String?) {
        switch rawValue?.lowercased() {
        case "off": self = .off
        case "console": self = .consoleOnly
        case "network": self = .networkOnly
        default: self = .full
        }
    }

    static var current: NetexShimMode {
        NetexShimMode(rawValue: ProcessInfo.processInfo.environment["NETEX_SHIMS"])
    }

    var installsConsole: Bool {
        self == .consoleOnly || self == .full
    }

    var installsNetwork: Bool {
        self == .networkOnly || self == .full
    }

    var installsExtension: Bool {
        self == .full
    }
}
