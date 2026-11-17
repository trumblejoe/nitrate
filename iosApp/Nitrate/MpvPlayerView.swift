import Foundation
import Libmpv
import NitrateKit
import UIKit

/// The layer mpv draws into (Vulkan over MoltenVK). Ignores the 1×1 drawable size MoltenVK sets
/// to finish a frame early, which would otherwise make the picture flicker.
final class MpvMetalLayer: CAMetalLayer {
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set { if newValue.width > 1 && newValue.height > 1 { super.drawableSize = newValue } }
    }
}

/// The view the player screen shows while mpv plays.
final class MpvView: UIView {
    let metalLayer = MpvMetalLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        metalLayer.contentsScale = UIScreen.main.nativeScale
        metalLayer.framebufferOnly = true
        metalLayer.backgroundColor = UIColor.black.cgColor
        layer.addSublayer(metalLayer)
    }

    required init?(coder: NSCoder) { nil }

    override func layoutSubviews() {
        super.layoutSubviews()
        metalLayer.frame = bounds
    }
}

/// One libmpv player for the Kotlin player screen (NitrateMpv): styled ASS subtitles drawn by
/// libass, DTS/TrueHD, Hi10P and every container FFmpeg reads, hardware decoding through
/// VideoToolbox where the device has it. MPVKit's LGPL build only (never MPVKit-GPL).
final class NitrateMpvPlayer: NSObject, NitrateMpv {
    private let mpvView = MpvView(frame: .zero)
    private var handle: OpaquePointer?
    private let events = DispatchQueue(label: "mpv-events")

    override init() {
        super.init()
        guard let h = mpv_create() else { return }
        handle = h
        var layer = mpvView.metalLayer
        mpv_set_option(h, "wid", MPV_FORMAT_INT64, &layer)
        mpv_set_option_string(h, "vo", "gpu-next")
        mpv_set_option_string(h, "gpu-api", "vulkan")
        mpv_set_option_string(h, "gpu-context", "moltenvk")
        mpv_set_option_string(h, "hwdec", "videotoolbox")
        // Stay on the last frame at the end, so the end can be noticed (eof-reached).
        mpv_set_option_string(h, "keep-open", "yes")
        mpv_set_option_string(h, "subs-fallback", "yes")
        mpv_set_option_string(h, "audio-display", "no")
        mpv_request_log_messages(h, "no")
        mpv_initialize(h)
        // Events must be read or they pile up; nothing here needs them otherwise.
        mpv_set_wakeup_callback(h, { context in
            guard let context else { return }
            Unmanaged<NitrateMpvPlayer>.fromOpaque(context).takeUnretainedValue().drainEvents()
        }, Unmanaged.passUnretained(self).toOpaque())
    }

    private func drainEvents() {
        events.async { [weak self] in
            while let h = self?.handle, let event = mpv_wait_event(h, 0), event.pointee.event_id != MPV_EVENT_NONE {}
        }
    }

    private func command(_ args: [String]) {
        guard let h = handle else { return }
        var cArgs: [UnsafeMutablePointer<CChar>?] = args.map { strdup($0) }
        cArgs.append(nil)
        defer { cArgs.forEach { free($0) } }
        cArgs.withUnsafeMutableBufferPointer { buffer in
            buffer.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>?.self, capacity: buffer.count) { pointer in
                _ = mpv_command(h, pointer)
            }
        }
    }

    private func setString(_ name: String, _ value: String) {
        guard let h = handle else { return }
        mpv_set_property_string(h, name, value)
    }

    private func double(_ name: String) -> Double {
        guard let h = handle else { return 0 }
        var value = 0.0
        mpv_get_property(h, name, MPV_FORMAT_DOUBLE, &value)
        return value
    }

    private func flag(_ name: String) -> Bool {
        guard let h = handle else { return false }
        var value: Int32 = 0
        mpv_get_property(h, name, MPV_FORMAT_FLAG, &value)
        return value != 0
    }

    func view() -> UIView { mpvView }

    func load(url: String, authorization: String, startSeconds: Double, audioId: Int32, subtitleId: Int32, subtitleUrls: [String]) {
        // One header line, appended as it is (the value has commas, which a plain list would split).
        command(["change-list", "http-header-fields", "clr", ""])
        command(["change-list", "http-header-fields", "append", "Authorization: \(authorization)"])
        command(["change-list", "sub-files", "clr", ""])
        for subtitle in subtitleUrls { command(["change-list", "sub-files", "append", subtitle]) }
        setString("start", String(format: "%.3f", startSeconds))
        setString("aid", audioId < 0 ? "auto" : String(audioId))
        setString("sid", subtitleId <= 0 ? "no" : String(subtitleId))
        setString("pause", "no")
        command(["loadfile", url, "replace"])
    }

    func setAudio(id: Int32) { setString("aid", id < 0 ? "auto" : String(id)) }

    func setSubtitle(id: Int32) { setString("sid", id <= 0 ? "no" : String(id)) }

    func play() { setString("pause", "no") }

    func pause() { setString("pause", "yes") }

    func seek(seconds: Double) { command(["seek", String(format: "%.3f", seconds), "absolute"]) }

    func position() -> Double { double("time-pos") }

    func duration() -> Double { double("duration") }

    func isPaused() -> Bool { flag("pause") }

    func isEnded() -> Bool { flag("eof-reached") }

    func release() {
        guard let h = handle else { return }
        handle = nil
        mpv_set_wakeup_callback(h, nil, nil)
        mpv_terminate_destroy(h)
    }
}
