// TODO(ios-cast): DRAFT, never compiled. Needs macOS to build and check against the SDK.
// Package (project.yml):
//   GoogleCast:
//     url: https://github.com/SRGSSR/google-cast-sdk   # SPM mirror of Google's Cast SDK; check it's current, or vendor Google's xcframework
//     from: 4.8.3
// Product: GoogleCast. Also Info.plist NSBonjourServices and NSLocalNetworkUsageDescription (see README.md).
//
// Talks to Jellyfin's own receiver app (core Cast.RECEIVER_APP_ID) on its channel (Cast.NAMESPACE);
// the messages themselves are built and read in Kotlin (MediaServer.castPlay/castCommand/castStatus).

import GoogleCast
import NitrateKit

final class NitrateCast: NSObject, NitrateCastSdk, GCKDiscoveryManagerListener, GCKSessionManagerListener, GCKGenericChannelDelegate {
    private static let receiverAppId = "F007D354"
    private static let namespace = "urn:x-cast:com.connectsdk"

    private var events: NitrateCastEvents?
    private var channel: GCKGenericChannel?

    func start(events: NitrateCastEvents) {
        self.events = events
        let criteria = GCKDiscoveryCriteria(applicationID: Self.receiverAppId)
        let options = GCKCastOptions(discoveryCriteria: criteria)
        options.physicalVolumeButtonsWillControlDeviceVolume = true
        GCKCastContext.setSharedInstanceWith(options)
        let context = GCKCastContext.sharedInstance()
        context.discoveryManager.add(self)
        context.sessionManager.add(self)
    }

    func discover(on: Bool) {
        let manager = GCKCastContext.sharedInstance().discoveryManager
        if on { manager.startDiscovery() } else { manager.stopDiscovery() }
        reportDevices()
    }

    func connect(deviceId: String) {
        let manager = GCKCastContext.sharedInstance().discoveryManager
        guard let device = manager.device(withUniqueID: deviceId) else { return }
        events?.connecting(deviceId: device.uniqueID, name: device.friendlyName ?? device.uniqueID)
        GCKCastContext.sharedInstance().sessionManager.startSession(with: device)
    }

    func disconnect() {
        GCKCastContext.sharedInstance().sessionManager.endSessionAndStopCasting(true)
    }

    func send(message: String) {
        var error: GCKError?
        channel?.sendTextMessage(message, error: &error)
    }

    // MARK: discovery

    func didUpdateDeviceList() { reportDevices() }

    private func reportDevices() {
        let manager = GCKCastContext.sharedInstance().discoveryManager
        var ids: [String] = [], names: [String] = [], descriptions: [String] = []
        for i in 0..<manager.deviceCount {
            let d = manager.device(at: i)
            ids.append(d.uniqueID)
            names.append(d.friendlyName ?? d.modelName ?? d.uniqueID)
            descriptions.append(d.modelName ?? "")
        }
        events?.devices(ids: ids, names: names, descriptions: descriptions)
    }

    // MARK: sessions

    func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKCastSession) {
        let channel = GCKGenericChannel(namespace: Self.namespace)
        channel.delegate = self
        session.add(channel)
        self.channel = channel
        let device = session.device
        events?.connected(deviceId: device.uniqueID, name: device.friendlyName ?? device.uniqueID)
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didResumeCastSession session: GCKCastSession) {
        self.sessionManager(sessionManager, didStart: session)
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKCastSession, withError error: Error?) {
        channel = nil
        events?.disconnected()
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKCastSession, withError error: Error) {
        channel = nil
        events?.disconnected()
    }

    // MARK: channel

    func cast(_ channel: GCKGenericChannel, didReceiveTextMessage message: String, withNamespace protocolNamespace: String) {
        events?.message(text: message)
    }
}
