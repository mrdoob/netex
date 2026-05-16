import SwiftUI

@main
struct NetexIOSApp: App {
    var body: some Scene {
        WindowGroup {
            BrowserView()
                .ignoresSafeArea(.keyboard, edges: .bottom)
        }
    }
}
