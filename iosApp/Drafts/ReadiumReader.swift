// TODO(ios-readium): DRAFT, never compiled. Needs macOS to build and check against Readium's API.
// Package (project.yml):
//   Readium:
//     url: https://github.com/readium/swift-toolkit
//     from: 3.4.0
// Products: ReadiumShared, ReadiumStreamer, ReadiumNavigator, ReadiumAdapterGCDWebServer (BSD-3,
// already credited in core/OpenSource for Android's Readium).
//
// Opens a fetched EPUB full screen in Readium's EPUB navigator, from this device's last locator
// (JSON) else the server's progression, and reports each settled place back to Kotlin, which saves
// it on the device and on the server (OpenedBook.Epub.save).

import UIKit
import NitrateKit
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator
import ReadiumAdapterGCDWebServer

final class ReadiumReader: NSObject, NitrateBookReader {
    private let httpClient = DefaultHTTPClient()
    private lazy var assetRetriever = AssetRetriever(httpClient: httpClient)
    private lazy var opener = PublicationOpener(
        parser: DefaultPublicationParser(httpClient: httpClient, assetRetriever: assetRetriever, pdfFactory: DefaultPDFDocumentFactory())
    )

    func open(path: String, title: String, locator: String?, progression: Double, place: @escaping (String, KotlinDouble) -> Void) {
        Task { @MainActor in
            guard let url = FileURL(path: path, isDirectory: false) else { return }
            // TODO(ios-readium): check these result types against 3.4.
            guard case .success(let asset) = await assetRetriever.retrieve(url: url),
                  case .success(let publication) = await opener.open(asset: asset, allowUserInteraction: false) else { return }
            var start: Locator? = locator.flatMap { try? Locator(jsonString: $0) }
            if start == nil, progression > 0 { start = await publication.locate(progression: progression) }
            guard let navigator = try? EPUBNavigatorViewController(
                publication: publication,
                initialLocation: start,
                config: .init(preferences: EPUBPreferences(theme: .dark)),
                httpServer: GCDHTTPServer.shared
            ) else { return }
            let host = ReaderController(navigator: navigator, title: title, place: place)
            topController()?.present(host, animated: true)
        }
    }

    private func topController() -> UIViewController? {
        var c = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.rootViewController
        while let p = c?.presentedViewController { c = p }
        return c
    }
}

/// The navigator full screen with a Done button; saves the place 1.5 s after it settles and on closing.
private final class ReaderController: UINavigationController, EPUBNavigatorDelegate {
    private let navigator: EPUBNavigatorViewController
    private let place: (String, KotlinDouble) -> Void
    private var pending: DispatchWorkItem?
    private var last: Locator?

    init(navigator: EPUBNavigatorViewController, title: String, place: @escaping (String, KotlinDouble) -> Void) {
        self.navigator = navigator
        self.place = place
        super.init(rootViewController: navigator)
        modalPresentationStyle = .fullScreen
        navigator.title = title
        navigator.delegate = self
        navigator.navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak self] _ in self?.close() })
    }

    required init?(coder: NSCoder) { fatalError() }

    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        last = locator
        pending?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.save() }
        pending = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
    }

    func navigator(_ navigator: Navigator, presentError error: NavigatorError) {}

    private func save() {
        guard let l = last, let json = l.jsonString else { return }
        place(json, KotlinDouble(value: l.locations.totalProgression ?? 0))
    }

    private func close() {
        pending?.cancel()
        save()
        dismiss(animated: true)
    }
}
