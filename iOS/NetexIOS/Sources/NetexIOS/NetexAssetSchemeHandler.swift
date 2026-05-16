import Foundation
import MobileCoreServices
import UniformTypeIdentifiers
import WebKit

final class NetexAssetSchemeHandler: NSObject, WKURLSchemeHandler {
    static let scheme = "netex-assets"

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url, let fileURL = AssetLoader.fileURL(forAssetPath: assetPath(from: url)) else {
            urlSchemeTask.didFailWithError(NSError(domain: "NetexAssetScheme", code: 404))
            return
        }
        do {
            let data = try Data(contentsOf: fileURL)
            let response = URLResponse(url: url, mimeType: mimeType(for: fileURL), expectedContentLength: data.count, textEncodingName: nil)
            urlSchemeTask.didReceive(response)
            urlSchemeTask.didReceive(data)
            urlSchemeTask.didFinish()
        } catch {
            urlSchemeTask.didFailWithError(error)
        }
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}

    private func assetPath(from url: URL) -> String {
        var path = url.path
        if path.hasPrefix("/") { path.removeFirst() }
        if path.hasPrefix("NetexAssets/") { return path }
        return "NetexAssets/" + path
    }

    private func mimeType(for url: URL) -> String {
        if #available(iOS 14.0, *) {
            return UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        }
        return "application/octet-stream"
    }
}
