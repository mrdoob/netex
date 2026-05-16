import SwiftUI

struct BrowserView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> BrowserViewController {
        BrowserViewController()
    }

    func updateUIViewController(_ uiViewController: BrowserViewController, context: Context) {}
}
