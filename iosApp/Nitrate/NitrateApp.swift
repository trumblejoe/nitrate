import AVFoundation
import NitrateKit
import SwiftUI

/// Hosts the Kotlin app (Compose Multiplatform) full screen.
@main
struct NitrateApp: App {
    init() {
        // Sound plays with the silent switch on, like any video app.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    }

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        AppKt.MainViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
