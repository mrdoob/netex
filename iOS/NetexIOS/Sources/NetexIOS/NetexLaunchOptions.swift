import Foundation

struct NetexLaunchOptions {
    let reset: Bool
    let initialURL: URL?

    init(arguments: [String] = ProcessInfo.processInfo.arguments) {
        reset = arguments.contains("--netex-reset")

        if let index = arguments.firstIndex(of: "--netex-url"),
           arguments.indices.contains(index + 1) {
            initialURL = URL(string: arguments[index + 1])
        } else {
            initialURL = nil
        }
    }
}
