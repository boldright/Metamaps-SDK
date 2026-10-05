#if os(iOS)
import Foundation
import MetamapsPositioning
import SafariServices
import UIKit
import WebKit

/// A UIKit view that shows the published Metamaps map for `mapSlug`.
///
/// For maps with indoor positioning, it can also show the user's location while the app is in use.
///
/// Use it only from the main actor. `load()` waits for a successful HTTP response and the end of the main-frame
/// load, but not for the `.ready` event.
@MainActor
public final class MetamapsMapView: UIView {
    /// The delegate that receives events and decides how to handle external links.
    public weak var delegate: MetamapsMapViewDelegate?
    /// An asynchronous stream of the events this view sends.
    public nonisolated let events: AsyncStream<MetamapsMapViewEvent>
    /// The current main-frame load state.
    public private(set) var loadState: MapViewLoadState = .idle

    /// The embedded WKWebView owned by the SDK. Do not change its navigation delegate.
    public let webView: WKWebView
    private let configuration: MetamapsMapViewConfiguration
    private let positioningClient: MetamapsPositioningClient
    private let continuation: AsyncStream<MetamapsMapViewEvent>.Continuation
    private let scriptProxy = WeakScriptMessageHandler()
    private var bridgeValidator: BridgeValidator
    private var outboundSequence: Int64 = 0
    private var positioningEventTask: Task<Void, Never>?
    private var beaconSignalBridgeTask: Task<Void, Never>?
    private var pendingBeaconSignals: [BeaconSignalReading]?
    private var pendingCenterReticleRequests: [UUID: PendingCenterReticleRequest] = [:]
    private var loadContinuation: CheckedContinuation<Void, Error>?
    private var retryDelayTask: Task<Void, Error>?
    private var loadGeneration = 0
    private var positioningPreparedGeneration: Int?
    private var activeNavigation: WKNavigation?
    private var disposed = false
    /// Waits for beacon signals and retries automatic positioning. Never advances in a `userAction` map view.
    private var automaticLoop = AutomaticPositioningLoop()
    private var automaticTimerTask: Task<Void, Never>?
    /// `capabilities` re-evaluated on returning to the foreground. Sent again only when they differ from the
    /// last value.
    private var lastSentCapabilities: CapabilityReport?
    /// The automatic positioning configuration last announced in `bridge.hello`, used to decide whether a
    /// correction is needed.
    private var lastSentAutomaticPositioning: Bool?
    private var foregroundObserver: NSObjectProtocol?
    private var backgroundObserver: NSObjectProtocol?

    /// Creates a map view from a validated configuration.
    public init(configuration: MetamapsMapViewConfiguration) {
        self.configuration = configuration
        positioningClient = MetamapsPositioningClient(configuration: configuration.positioningConfiguration)
        bridgeValidator = BridgeValidator(requestedGroupId: configuration.groupId)
        let stream = AsyncStream.makeStream(of: MetamapsMapViewEvent.self, bufferingPolicy: .bufferingNewest(64))
        events = stream.stream
        continuation = stream.continuation

        let contentController = WKUserContentController()
        let webConfiguration = WKWebViewConfiguration()
        webConfiguration.userContentController = contentController
        webConfiguration.defaultWebpagePreferences.allowsContentJavaScript = true
        webConfiguration.websiteDataStore = .default()
        webConfiguration.applicationNameForUserAgent = makeMetamapsUserAgent(
            applicationVersion: MetamapsSDK.version,
            appendix: configuration.userAgentAppendix)
        webConfiguration.allowsInlineMediaPlayback = true
        webConfiguration.mediaTypesRequiringUserActionForPlayback = .audio
        webView = WKWebView(frame: .zero, configuration: webConfiguration)
        if #available(iOS 16.4, *) {
            webView.isInspectable = configuration.isWebViewInspectable
        }
        webView.isMultipleTouchEnabled = true
        webView.scrollView.isMultipleTouchEnabled = true
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        webView.scrollView.pinchGestureRecognizer?.isEnabled = false

        super.init(frame: .zero)
        scriptProxy.target = self
        contentController.add(scriptProxy, contentWorld: .page, name: Self.messageHandlerName)
        contentController.addUserScript(WKUserScript(
            source: Self.bootstrapScript, injectionTime: .atDocumentStart, forMainFrameOnly: true,
            in: .page))
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(webView)
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: trailingAnchor),
            webView.topAnchor.constraint(equalTo: topAnchor),
            webView.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        let positioningEvents = positioningClient.events
        positioningEventTask = Task { [weak self, positioningEvents] in
            for await event in positioningEvents {
                guard !Task.isCancelled else { break }
                self?.handlePositioningEvent(event)
            }
        }
        // Re-evaluate `capabilities` on returning to the foreground. With a single evaluation at handshake, the web map
        // could not switch to BLE after the user granted permission in the Settings app and came back.
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in self?.handleReturnToForeground() }
        }
        backgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in self?.handleEnterBackground() }
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    /// Loads the web map and waits until it succeeds or fails with `MetamapsError`.
    ///
    /// Cancelling the task stops the WebView load and automatic retries.
    public func load() async throws {
        guard !disposed else { throw bridgeError(.internalInvariantViolation, "load called after dispose") }
        try configuration.validate()
        failPendingCenterReticleRequests(
            bridgeError(.bridgeHandshakeFailed, "Map document changed during center reticle request"))
        loadGeneration += 1
        let generation = loadGeneration
        supersedeCurrentLoad()
        bridgeValidator.reset()
        outboundSequence = 0
        if loadState != .loading { emit(.loadState(.loading)) }
        do {
            try await withTaskCancellationHandler {
                let request = try makeLoadRequest()
                try await runLoadLoop(generation: generation, request: request)
            } onCancel: {
                Task { @MainActor [weak self] in
                    self?.cancelLoad(generation: generation)
                }
            }
        } catch is CancellationError {
            cancelLoad(generation: generation)
            throw CancellationError()
        }
    }

    /// Reloads the main frame with the current configuration.
    public func reload() async throws { try await load() }

    /// Requests permissions if needed and starts positioning while the app is in use.
    ///
    /// In an automatic positioning configuration, a direct call from the host also joins the wait-for-signals and
    /// retry cycle that starts after scanning begins. Failures are thrown to the caller as before.
    public func startPositioning(requestAuthorization: Bool = true) async throws {
        guard !disposed else { throw bridgeError(.internalInvariantViolation, "startPositioning called after dispose") }
        if positioningClient.status == .unconfigured { try await positioningClient.configure() }
        try validateManifestIdentityIfReady()
        if requestAuthorization { try await positioningClient.requestAuthorization(.foregroundNavigation) }
        try await positioningClient.start()
        if isAutomaticPositioningEnabled {
            runAutomaticEffects(automaticLoop.positioningStarted())
        }
    }

    /// Requests the OS permissions positioning needs. Call it from an explicit user action.
    public func requestPositioningAuthorization() async throws {
        guard !disposed else { throw bridgeError(.internalInvariantViolation, "requestPositioningAuthorization called after dispose") }
        if positioningClient.status == .unconfigured { try await positioningClient.configure() }
        try validateManifestIdentityIfReady()
        try await positioningClient.requestAuthorization(.foregroundNavigation)
    }

    /// Stops positioning, including the automatic positioning retry cycle.
    public func stopPositioning() {
        automaticTimerTask?.cancel()
        automaticTimerTask = nil
        _ = automaticLoop.stop()
        positioningClient.stop()
    }

    /// Opens an allowlisted URL with the SDK's default handling.
    public func openExternalLink(_ url: URL) {
        guard !disposed, isAllowedMetamapsExternalLink(url) else { return }
        let scheme = url.scheme?.lowercased()
        guard scheme == "http" || scheme == "https" else {
            UIApplication.shared.open(url)
            return
        }
        guard let presenter = topViewController(in: window?.rootViewController),
              presenter.viewIfLoaded?.window != nil,
              !presenter.isBeingDismissed else {
            UIApplication.shared.open(url)
            return
        }
        presenter.present(SFSafariViewController(url: url), animated: true)
    }

    /// Resets PDR step timing and the estimator state at a known route start point.
    public func resetPedestrianRoute() throws {
        guard !disposed else {
            throw bridgeError(.internalInvariantViolation, "resetPedestrianRoute called after dispose")
        }
        try positioningClient.resetPedestrianRoute()
    }

    /// Selects the floor shown in the web map. `nil` clears the selection.
    public func selectFloor(_ floorId: UUID?) throws {
        try sendHostCommand("map.selectFloor", payload: OptionalIdPayload(floorId: floorId))
    }

    /// Shows the spot with the given public spot stable key.
    public func showSpot(_ spotId: String) throws {
        guard isValidMetamapsSpotStableKey(spotId) else {
            throw MetamapsError.configurationInvalid("spotId must be a public spots.v2 stable key.")
        }
        try sendHostCommand("map.showSpot", payload: SpotIdPayload(spotId: spotId))
    }

    /// Sets the spot with the given public spot stable key as the route destination. `nil` clears the route.
    public func setDestination(_ spotId: String?) throws {
        if let spotId, !isValidMetamapsSpotStableKey(spotId) {
            throw MetamapsError.configurationInvalid("spotId must be a public spots.v2 stable key.")
        }
        try sendHostCommand("map.setDestination", payload: OptionalSpotIdPayload(spotId: spotId))
    }

    /// Changes the web map's display language to the given BCP 47 language tag.
    public func setLanguage(_ language: String) throws {
        try MetamapsMapViewConfiguration.validateLanguage(language)
        try sendHostCommand("map.setLanguage", payload: LanguagePayload(language: language))
    }

    /// Shows or hides the center reticle. Hidden by default.
    public func setCenterReticleEnabled(_ enabled: Bool) throws {
        try sendHostCommand("map.setCenterReticle", payload: CenterReticleVisibilityPayload(enabled: enabled))
    }

    /// Sends a host command used only by Metamaps tooling, such as the BLE test diagnostic overlay.
    /// The web runtime rejects these commands outside BLE test mode.
    @_spi(MetamapsInternal)
    public func sendInternalBridgeCommand<Payload: Encodable>(_ type: String, payload: Payload) throws {
        try sendHostCommand(type, payload: payload)
    }

    /// Gets the candidate on the selected floor under the reticle at the time of the call, once.
    /// Returns `nil` when the reticle is hidden, all floors are shown, or the reticle does not hit a floor.
    public func requestCenterReticleCandidate() async throws -> MapCenterReticleCandidate? {
        guard !disposed else {
            throw bridgeError(.internalInvariantViolation, "requestCenterReticleCandidate called after dispose")
        }
        guard bridgeValidator.handshakeComplete else {
            throw bridgeError(.bridgeHandshakeFailed, "MapView is not ready.")
        }
        let requestId = UUID()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let pending = PendingCenterReticleRequest(continuation: continuation)
                pending.timeoutTask = Task { @MainActor [weak self, weak pending] in
                    try? await Task.sleep(for: .seconds(5))
                    guard !Task.isCancelled, let self, let pending,
                          self.pendingCenterReticleRequests[requestId] === pending else { return }
                    self.pendingCenterReticleRequests.removeValue(forKey: requestId)
                    pending.continuation.resume(throwing: self.bridgeError(
                        .bridgeHandshakeFailed,
                        "Center reticle candidate request timed out."))
                }
                pendingCenterReticleRequests[requestId] = pending
                do {
                    try sendHostCommand(
                        "map.requestCenterReticleCandidate",
                        payload: CenterReticleRequestPayload(requestId: requestId))
                } catch {
                    pendingCenterReticleRequests.removeValue(forKey: requestId)
                    pending.timeoutTask?.cancel()
                    continuation.resume(throwing: error)
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.cancelCenterReticleRequest(requestId, error: CancellationError())
            }
        }
    }

    /// Releases positioning, the WebView, and the streams. Safe to call more than once.
    public func dispose() {
        guard !disposed else { return }
        disposed = true
        loadGeneration += 1
        retryDelayTask?.cancel()
        retryDelayTask = nil
        positioningEventTask?.cancel()
        positioningEventTask = nil
        beaconSignalBridgeTask?.cancel()
        beaconSignalBridgeTask = nil
        pendingBeaconSignals = nil
        automaticTimerTask?.cancel()
        automaticTimerTask = nil
        if let foregroundObserver {
            NotificationCenter.default.removeObserver(foregroundObserver)
            self.foregroundObserver = nil
        }
        if let backgroundObserver {
            NotificationCenter.default.removeObserver(backgroundObserver)
            self.backgroundObserver = nil
        }
        failPendingCenterReticleRequests(
            bridgeError(.bridgeHandshakeFailed, "MapView disposed during center reticle request"))
        positioningClient.dispose()
        webView.stopLoading()
        activeNavigation = nil
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        webView.configuration.userContentController.removeScriptMessageHandler(forName: Self.messageHandlerName, contentWorld: .page)
        loadContinuation?.resume(throwing: bridgeError(.webContentLoadFailed, "MapView disposed during load"))
        loadContinuation = nil
        emit(.loadState(.disposed))
        continuation.finish()
        delegate = nil
    }

    private func runLoadLoop(generation: Int, request: URLRequest) async throws {
        var retryFailureCount = 0
        while true {
            try Task.checkCancellation()
            guard generation == loadGeneration, !disposed else { throw CancellationError() }
            do {
                try await runLoadAttempt(request: request, generation: generation)
                return
            } catch let failure as MapViewLoadAttemptFailure {
                guard generation == loadGeneration, !disposed else { throw CancellationError() }
                let shouldRetry = configuration.retryPolicy == .automatic
                    && failure.shouldRetryAutomatically
                if !shouldRetry {
                    failLoadImmediately(failure.error)
                    throw failure.error
                }
                emit(.error(failure.error))
                retryFailureCount += 1
                let seconds = MapViewRetryPlan.delaySeconds(afterFailure: retryFailureCount)
                let delay = Task<Void, Error> {
                    try await Task.sleep(for: .seconds(seconds))
                }
                retryDelayTask = delay
                defer {
                    if generation == loadGeneration { retryDelayTask = nil }
                }
                try await delay.value
            }
        }
    }

    private func makeLoadRequest() throws -> URLRequest {
        var request = URLRequest(url: try mapURL())
        request.timeoutInterval = 30
        return request
    }

    private func runLoadAttempt(request: URLRequest, generation: Int) async throws {
        try Task.checkCancellation()
        guard generation == loadGeneration, !disposed else { throw CancellationError() }
        try await withCheckedThrowingContinuation { next in
            loadContinuation = next
            activeNavigation = webView.load(request)
        }
    }

    private func supersedeCurrentLoad() {
        webView.stopLoading()
        activeNavigation = nil
        retryDelayTask?.cancel()
        retryDelayTask = nil
        loadContinuation?.resume(throwing: CancellationError())
        loadContinuation = nil
    }

    private func cancelLoad(generation: Int) {
        guard generation == loadGeneration, !disposed else { return }
        loadGeneration += 1
        webView.stopLoading()
        activeNavigation = nil
        retryDelayTask?.cancel()
        retryDelayTask = nil
        loadContinuation?.resume(throwing: CancellationError())
        loadContinuation = nil
        emit(.loadState(.idle))
    }

    /// Prepares positioning once per load, after `map.ready` shows that the map has indoor positioning.
    private func preparePositioningIfNeeded(for ready: BridgeReadyContext) {
        let verifiedRevision = positioningClient.manifestIdentity
            .flatMap { $0.mapId == ready.mapId && $0.groupId == ready.groupId ? $0.revision : nil }
        guard shouldPreparePositioning(
            policy: configuration.positioningPolicy,
            manifestRevision: ready.manifestRevision,
            bleTest: configuration.bleTest,
            verifiedManifestRevision: verifiedRevision),
            positioningPreparedGeneration != loadGeneration else { return }
        let generation = loadGeneration
        positioningPreparedGeneration = generation
        Task { @MainActor [weak self] in
            await self?.configurePositioningWithoutFailingMap()
            guard let self, generation == self.loadGeneration else { return }
            self.refreshAutomaticPositioningAnnouncement()
        }
    }

    private func configurePositioningWithoutFailingMap() async {
        guard configuration.positioningPolicy != .disabled, !disposed else { return }
        do {
            try await positioningClient.configure()
            try validateManifestIdentityIfReady()
        } catch is CancellationError {
            return
        } catch let error as MetamapsError {
            emit(.error(error))
            send(type: "positioning.error", payload: error)
        } catch {
            let typed = bridgeError(.manifestUnavailable, String(describing: error))
            emit(.error(typed))
            send(type: "positioning.error", payload: typed)
        }
    }

    private func handlePositioningEvent(_ event: MetamapsPositioningEvent) {
        switch event {
        case .position(let update):
            // Do not make host notifications depend on the WebView bridge being ready. Hiding them until then
            // would leave the operator with only the status text and no way to diagnose positioning.
            emit(.position(update))
            guard bridgeValidator.handshakeComplete else { return }
            do {
                try validateManifestIdentityIfReady()
                send(type: "positioning.position", payload: update)
            } catch let error as MetamapsError {
                emit(.error(error))
            } catch {}
        case .beaconSignals(let readings):
            guard configuration.effectiveBeaconDiagnostics else { return }
            scheduleBeaconSignalPresentation(readings)
        case .motionHeading(let reading):
            emit(.motionHeading(reading))
            if bridgeValidator.handshakeComplete {
                send(type: "positioning.motionHeading", payload: reading)
            }
        case .status(let status):
            emit(.positioningStatus(status))
            send(type: "positioning.status", payload: ["status": status.rawValue])
        case .capabilities(let report):
            lastSentCapabilities = report
            emit(.capabilities(report))
            send(type: "positioning.capabilities", payload: report)
            // Finishing configure or settling permissions can change the automatic positioning configuration.
            // Correct the hello announcement.
            refreshAutomaticPositioningAnnouncement()
        case .error(let error):
            emit(.error(error))
            send(type: "positioning.error", payload: error)
        @unknown default:
            break
        }
    }

    private func receive(_ message: WKScriptMessage) {
        guard !disposed, message.frameInfo.isMainFrame, isAllowed(message.frameInfo.securityOrigin) else {
            emit(.error(bridgeError(.bridgeHandshakeFailed, "Rejected bridge message origin or frame.")))
            return
        }
        do {
            let data = try JSONSerialization.data(withJSONObject: message.body)
            let envelope = try JSONDecoder().decode(BridgeEnvelope.self, from: data)
            let ready = try bridgeValidator.validate(envelope)
            if let ready {
                try validateManifestIdentityIfReady()
                completeHandshake(with: ready)
                return
            }
            try handleInbound(envelope)
        } catch let error as MetamapsError {
            emit(.error(error))
            send(type: "positioning.error", payload: error)
        } catch {
            emit(.error(bridgeError(.bridgeHandshakeFailed, String(describing: error))))
        }
    }

    private func handleInbound(_ envelope: BridgeEnvelope) throws {
        switch envelope.type {
        case "positioning.requestAuthorization":
            switch configuration.positioningPolicy {
            case .disabled:
                throw bridgeError(.bleUnsupported, "Positioning is disabled by host policy.")
            case .hostControlled:
                emit(.positioningAuthorizationRequested)
            case .userInitiated:
                Task { [weak self] in
                    do { try await self?.requestPositioningAuthorization() }
                    catch let error as MetamapsError { self?.emit(.error(error)); self?.send(type: "positioning.error", payload: error) }
                    catch { self?.emit(.error(self?.bridgeError(.scanStartFailed, String(describing: error))
                                              ?? MetamapsError.configurationInvalid("MapView released"))) }
                }
            }
        case "positioning.start":
            // In an automatic positioning configuration, start and stop go through the bounded retry loop.
            // Starts from a button tap also arrive as `positioning.start`, so branch on the map view's
            // configuration, not on where the request came from.
            switch resolveAutomaticPositioningStartAction(
                policy: configuration.positioningPolicy,
                isAutomaticConfiguration: isAutomaticPositioningEnabled
            ) {
            case .reject:
                throw bridgeError(.bleUnsupported, "Positioning is disabled by host policy.")
            case .notifyHost:
                emit(.positioningStartRequested)
            case .runAutomaticLoop:
                runAutomaticEffects(automaticLoop.start())
            case .startDirectly:
                Task { [weak self] in
                    do { try await self?.startPositioning(requestAuthorization: true) }
                    catch let error as MetamapsError { self?.emit(.error(error)); self?.send(type: "positioning.error", payload: error) }
                    catch { self?.emit(.error(self?.bridgeError(.scanStartFailed, String(describing: error))
                                              ?? MetamapsError.configurationInvalid("MapView released"))) }
                }
            }
        case "positioning.stop":
            // Stop the retry cycle as well as any running positioning. Stop requests are not distinguished by kind.
            runAutomaticEffects(automaticLoop.stop())
            stopPositioning()
        case "map.floorChanged":
            if let event: FloorChangedEvent = try decodePayload(envelope.payload) { emit(.floorChanged(event)) }
        case "map.spotSelected":
            if let event: SpotSelectedEvent = try decodePayload(envelope.payload) { emit(.spotSelected(event)) }
        case "map.routeChanged":
            if let event: RouteChangedEvent = try decodePayload(envelope.payload) { emit(.routeChanged(event)) }
        case "map.centerReticleCandidate":
            handleCenterReticleCandidate(envelope.payload)
        case "map.externalLinkRequested":
            // After the handshake, the web page calls preventDefault on link taps and sends them through this path.
            // Route them through the same hook-then-default flow as intercepted navigation.
            if case .object(let payload) = envelope.payload, case .string(let raw) = payload["url"], let url = URL(string: raw) {
                handleExternalLink(url)
            }
        case "map.error":
            // The web map could not complete a host command, such as showing an unknown spot. Report it only to
            // the host: sending it back as positioning.error would make the web map drop the current location.
            emit(.error(envelope.mapOperationError))
        default:
            // Minor bridge versions may add optional events. The validated envelope is safe to ignore.
            break
        }
    }

    private func validateManifestIdentityIfReady() throws {
        guard let ready = bridgeValidator.ready, let identity = positioningClient.manifestIdentity else { return }
        guard ready.mapId == identity.mapId, ready.groupId == identity.groupId else {
            throw bridgeError(.bridgeMapMismatch, "Web map and positioning manifest identities differ.")
        }
        if let revision = ready.manifestRevision, revision != identity.revision {
            throw bridgeError(.bridgeMapMismatch, "Web map and positioning manifest revisions differ.")
        }
    }

    private func completeHandshake(with ready: BridgeReadyContext) {
        sendHello { [weak self] delivered in
            guard let self, delivered, self.bridgeValidator.completeHandshake(with: ready) else { return }
            self.emit(.ready(.init(
                mapId: ready.mapId,
                groupId: ready.groupId,
                configRevision: ready.configRevision,
                manifestRevision: ready.manifestRevision)))
            if let update = self.positioningClient.latestUpdate {
                self.send(type: "positioning.position", payload: update)
            }
            self.preparePositioningIfNeeded(for: ready)
        }
    }

    private func sendHello(
        completion: (@MainActor @Sendable (Bool) -> Void)? = nil
    ) {
        let capabilities = positioningClient.capabilities()
        let automaticPositioning = isAutomaticPositioningEnabled
        lastSentCapabilities = capabilities
        lastSentAutomaticPositioning = automaticPositioning
        send(type: "bridge.hello", payload: BridgeHelloPayload(
            sdkVersion: MetamapsSDK.version,
            platform: "ios",
            capabilities: capabilities,
            automaticPositioning: automaticPositioning), completion: completion)
    }

    /// Whether this map view runs automatic positioning (`bridge.hello.payload.automaticPositioning`).
    private var isAutomaticPositioningEnabled: Bool {
        isAutomaticPositioningConfiguration(
            trigger: configuration.positioningStartTrigger,
            policy: configuration.positioningPolicy,
            bleTest: configuration.bleTest,
            canStartWithoutNewPrompt: canStartPositioningWithoutNewPrompt(
                capabilities: positioningClient.capabilities(),
                motionPolicy: configuration.positioningConfiguration.motionPolicy),
            hasVerifiedManifest: positioningClient.manifestIdentity != nil)
    }

    /// If the manifest download or permission decision finishes after the handshake, send the same `bridge.hello` again
    /// to correct the automatic positioning configuration in the runtime. If the first announcement said false and
    /// is never corrected, automatic positioning never starts for that page's lifetime.
    private func refreshAutomaticPositioningAnnouncement() {
        guard !disposed, bridgeValidator.handshakeComplete else { return }
        guard isAutomaticPositioningEnabled != lastSentAutomaticPositioning else { return }
        sendHello()
    }

    // ── Automatic positioning retry loop ─────────────────────────────
    private func runAutomaticEffects(_ effects: [AutomaticPositioningEffect]) {
        for effect in effects {
            switch effect {
            case .start:
                startPositioningForAutomaticLoop()
            case .stop:
                positioningClient.stop()
            case .scheduleTimer(let delayMs):
                scheduleAutomaticTimer(delayMs: delayMs)
            case .cancelTimer:
                automaticTimerTask?.cancel()
                automaticTimerTask = nil
            }
        }
    }

    /// The 10-second wait for signals starts when scanning starts. Counting the time spent on permission dialogs
    /// or the manifest download as waiting would report the user as out of range before scanning even ran.
    private func startPositioningForAutomaticLoop() {
        Task { @MainActor [weak self] in
            do {
                // `positioningStarted` on success is emitted by `startPositioning`.
                try await self?.startPositioning(requestAuthorization: true)
            } catch let error as MetamapsError {
                guard let self, !disposed else { return }
                emit(.error(error))
                send(type: "positioning.error", payload: error)
                runAutomaticEffects(automaticLoop.startFailed())
            } catch {
                guard let self, !disposed else { return }
                runAutomaticEffects(automaticLoop.startFailed())
            }
        }
    }

    private func scheduleAutomaticTimer(delayMs: Int) {
        automaticTimerTask?.cancel()
        automaticTimerTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(delayMs))
            guard !Task.isCancelled, let self, !disposed else { return }
            automaticTimerTask = nil
            let observed = positioningClient.hasObservedRegisteredBeaconSinceStart
            runAutomaticEffects(automaticLoop.timerFired(observedRegisteredBeacon: observed))
        }
    }

    /// Returned to the foreground: re-evaluate `capabilities` and retry automatic positioning immediately.
    private func handleReturnToForeground() {
        guard !disposed else { return }
        let capabilities = positioningClient.capabilities()
        if capabilities != lastSentCapabilities, bridgeValidator.handshakeComplete {
            lastSentCapabilities = capabilities
            emit(.capabilities(capabilities))
            send(type: "positioning.capabilities", payload: capabilities)
        }
        refreshAutomaticPositioningAnnouncement()
        runAutomaticEffects(automaticLoop.returnToForeground())
    }

    /// Moved to the background. Retries run only in the foreground, so stop the timer and wait to return.
    private func handleEnterBackground() {
        guard !disposed else { return }
        runAutomaticEffects(automaticLoop.enterBackground())
    }

    /// Coalesces multiple iBeacon constraint callbacks, keeping only the latest. Delivers at most 5 Hz to both the
    /// SwiftUI delegate and WebKit/Babylon so that publish, JSON encoding, and JavaScript tasks do not pile up
    /// on the main actor, where gesture recognizers run.
    private func scheduleBeaconSignalPresentation(_ readings: [BeaconSignalReading]) {
        pendingBeaconSignals = readings
        guard beaconSignalBridgeTask == nil else { return }
        beaconSignalBridgeTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled, let self else { return }
            let next = pendingBeaconSignals
            pendingBeaconSignals = nil
            beaconSignalBridgeTask = nil
            if let next {
                emit(.beaconSignals(next))
                if bridgeValidator.handshakeComplete {
                    send(type: "positioning.beaconSignals", payload: next)
                }
            }
        }
    }

    private func sendHostCommand<T: Encodable>(_ type: String, payload: T) throws {
        guard !disposed else { throw bridgeError(.internalInvariantViolation, "\(type) called after dispose") }
        guard bridgeValidator.handshakeComplete else {
            throw bridgeError(.bridgeHandshakeFailed, "MapView handshake is not complete.")
        }
        send(type: type, payload: payload)
    }

    private func handleCenterReticleCandidate(_ payload: BridgeJSONValue?) {
        do {
            guard let response: CenterReticleCandidatePayload = try decodePayload(payload),
                  let pending = pendingCenterReticleRequests.removeValue(forKey: response.requestId) else { return }
            pending.timeoutTask?.cancel()
            guard let candidate = response.candidate else {
                pending.continuation.resume(returning: nil)
                return
            }
            guard (-180...180).contains(candidate.longitude),
                  (-90...90).contains(candidate.latitude) else {
                pending.continuation.resume(throwing: bridgeError(
                    .bridgeHandshakeFailed,
                    "Center reticle candidate coordinates are out of range."))
                return
            }
            let local = try positioningClient.manifestLocalPosition(
                longitude: candidate.longitude,
                latitude: candidate.latitude,
                floorId: candidate.floorId)
            pending.continuation.resume(returning: .init(
                floorId: candidate.floorId,
                longitude: candidate.longitude,
                latitude: candidate.latitude,
                local: local))
        } catch {
            if case .object(let object) = payload,
               case .string(let rawRequestId) = object["requestId"],
               let requestId = UUID(uuidString: rawRequestId),
               let pending = pendingCenterReticleRequests.removeValue(forKey: requestId) {
                pending.timeoutTask?.cancel()
                pending.continuation.resume(throwing: error)
            }
        }
    }

    private func cancelCenterReticleRequest(_ requestId: UUID, error: Error) {
        guard let pending = pendingCenterReticleRequests.removeValue(forKey: requestId) else { return }
        pending.timeoutTask?.cancel()
        pending.continuation.resume(throwing: error)
    }

    private func failPendingCenterReticleRequests(_ error: Error) {
        let pending = pendingCenterReticleRequests.values
        pendingCenterReticleRequests.removeAll()
        for request in pending {
            request.timeoutTask?.cancel()
            request.continuation.resume(throwing: error)
        }
    }

    private func send<T: Encodable>(
        type: String,
        payload: T,
        completion: (@MainActor @Sendable (Bool) -> Void)? = nil
    ) {
        do { send(type: type, bridgePayload: try .encode(payload), completion: completion) }
        catch {
            emit(.error(bridgeError(.internalInvariantViolation, "Unable to encode \(type): \(error)")))
            completion?(false)
        }
    }

    private func send(
        type: String,
        bridgePayload: BridgeJSONValue? = nil,
        completion: (@MainActor @Sendable (Bool) -> Void)? = nil
    ) {
        guard loadState == .loaded,
              type == "bridge.hello" || bridgeValidator.handshakeComplete else {
            completion?(false)
            return
        }
        outboundSequence += 1
        let ready = bridgeValidator.ready
        let envelope = BridgeEnvelope(type: type, mapId: ready?.mapId, groupId: ready?.groupId,
                                      sequence: outboundSequence, payload: bridgePayload)
        do {
            let data = try JSONEncoder.metamapsBridge.encode(envelope)
            let message = try JSONSerialization.jsonObject(with: data)
            Task { @MainActor [weak self, weak webView] in
                guard let webView else {
                    completion?(false)
                    return
                }
                do {
                    let result = try await webView.callAsyncJavaScript(
                        """
                        const receiver = window.__metamapsReceiveNativeMessage;
                        if (typeof receiver !== 'function') return false;
                        receiver(message);
                        return true;
                        """,
                        arguments: ["message": message], in: nil, contentWorld: .page)
                    completion?(result as? Bool == true)
                } catch {
                    self?.emit(.error(self?.bridgeError(.bridgeHandshakeFailed, String(describing: error))
                                      ?? MetamapsError.configurationInvalid("MapView released")))
                    completion?(false)
                }
            }
        } catch {
            emit(.error(bridgeError(.internalInvariantViolation, "Unable to encode bridge envelope: \(error)")))
            completion?(false)
        }
    }

    private func decodePayload<T: Decodable>(_ payload: BridgeJSONValue?) throws -> T? {
        guard let payload else { return nil }
        return try JSONDecoder().decode(T.self, from: JSONEncoder.metamapsBridge.encode(payload))
    }

    private func mapURL() throws -> URL {
        try makeMetamapsMapURL(configuration: configuration)
    }

    private func isAllowed(_ origin: WKSecurityOrigin) -> Bool {
        guard let configuredOrigin = MetamapsWebOrigin(url: configuration.baseURL) else { return false }
        return configuredOrigin == MetamapsWebOrigin(
            scheme: origin.protocol,
            host: origin.host,
            port: origin.port)
    }

    private func isAllowed(_ url: URL) -> Bool {
        guard let configuredOrigin = MetamapsWebOrigin(url: configuration.baseURL),
              let requestedOrigin = MetamapsWebOrigin(url: url)
        else { return false }
        return configuredOrigin == requestedOrigin
    }

    private func emit(_ event: MetamapsMapViewEvent) {
        if case .loadState(let state) = event { loadState = state }
        continuation.yield(event)
        delegate?.metamapsMapView(self, didReceive: event)
    }

    private func bridgeError(_ code: MetamapsError.Code, _ detail: String) -> MetamapsError {
        let recovery: (recoverable: Bool, action: MetamapsUserAction) = switch code {
        case .bridgeMapMismatch, .bridgeUnsupported, .internalInvariantViolation:
            (false, .checkConfiguration)
        case .bleUnsupported:
            (false, .selectLocationManually)
        default:
            (true, .retry)
        }
        return .init(code: code, message: "The embedded Metamaps bridge could not complete the requested operation.",
                     recoverable: recovery.recoverable, userAction: recovery.action, debugDetail: detail)
    }

    private static let messageHandlerName = "metamapNative"
    private static let bootstrapScript = """
    (() => {
      if (window.MetamapNativeBridge) return;
      Object.defineProperty(window, 'MetamapNativeBridge', {
        value: Object.freeze({
          postMessage(message) {
            window.webkit.messageHandlers.metamapNative.postMessage(message);
          }
        }), configurable: false, writable: false
      });
      window.__metamapsReceiveNativeMessage = function(message) {
        window.dispatchEvent(new CustomEvent('metamap:native-message', { detail: message }));
      };
    })();
    """
}

private struct BridgeHelloPayload: Encodable {
    let sdkVersion: String
    let platform: String
    let capabilities: CapabilityReport
    /// Backward-compatible addition in bridge 1.4. `true` only for map views configured for automatic positioning.
    let automaticPositioning: Bool
}

private struct OptionalIdPayload: Encodable {
    let floorId: String?
    init(floorId: UUID?) { self.floorId = floorId?.uuidString.lowercased() }
}

private struct SpotIdPayload: Encodable {
    let spotId: String
}

private struct OptionalSpotIdPayload: Encodable {
    let spotId: String?
}

private struct LanguagePayload: Encodable { let language: String }
private struct CenterReticleVisibilityPayload: Encodable { let enabled: Bool }
private struct CenterReticleRequestPayload: Encodable {
    let requestId: String
    init(requestId: UUID) { self.requestId = requestId.uuidString.lowercased() }
}

private struct CenterReticleCandidatePayload: Decodable {
    let requestId: UUID
    let candidate: WireCenterReticleCandidate?

    private enum CodingKeys: String, CodingKey {
        case requestId
        case candidate
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        requestId = try container.decode(UUID.self, forKey: .requestId)
        guard container.contains(.candidate) else {
            throw DecodingError.keyNotFound(
                CodingKeys.candidate,
                .init(codingPath: decoder.codingPath, debugDescription: "Missing candidate field"))
        }
        candidate = try container.decodeIfPresent(WireCenterReticleCandidate.self, forKey: .candidate)
    }
}

private struct WireCenterReticleCandidate: Decodable {
    let floorId: UUID
    let longitude: Double
    let latitude: Double
}

private final class PendingCenterReticleRequest {
    let continuation: CheckedContinuation<MapCenterReticleCandidate?, Error>
    var timeoutTask: Task<Void, Never>?

    init(continuation: CheckedContinuation<MapCenterReticleCandidate?, Error>) {
        self.continuation = continuation
    }
}

extension MetamapsMapView: WKNavigationDelegate, WKUIDelegate, ScriptMessageReceiver {
    fileprivate func didReceiveScriptMessage(_ message: WKScriptMessage) { receive(message) }

    public func webView(
        _ webView: WKWebView,
        requestMediaCapturePermissionFor origin: WKSecurityOrigin,
        initiatedByFrame frame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: @escaping @MainActor @Sendable (WKPermissionDecision) -> Void
    ) {
        let shouldGrant = shouldAutomaticallyGrantMetamapsMicrophonePermission(
            baseURL: configuration.baseURL,
            requestingScheme: origin.protocol,
            requestingHost: origin.host,
            requestingPort: origin.port,
            isMainFrame: frame.isMainFrame,
            isMicrophoneOnly: type == .microphone)
        decisionHandler(shouldGrant ? .grant : .prompt)
    }

    public func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        bridgeValidator.reset()
        outboundSequence = 0
        if loadState != .loading { emit(.loadState(.loading)) }
    }

    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        emit(.loadState(.loaded))
        if navigation === activeNavigation {
            activeNavigation = nil
            loadContinuation?.resume()
            loadContinuation = nil
        }
        sendHello()
    }

    public func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        handleNavigationFailure(navigation, error: error)
    }

    public func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        handleNavigationFailure(navigation, error: error)
    }

    public func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationResponse: WKNavigationResponse,
        decisionHandler: @escaping @MainActor @Sendable (WKNavigationResponsePolicy) -> Void
    ) {
        guard navigationResponse.isForMainFrame,
              let response = navigationResponse.response as? HTTPURLResponse,
              let classification = MapViewLoadFailureClassification.httpStatus(response.statusCode)
        else {
            decisionHandler(.allow)
            return
        }
        let error = makeLoadError(
            classification,
            detail: "Main-frame HTTP response returned status \(response.statusCode).")
        if let loadContinuation {
            self.loadContinuation = nil
            loadContinuation.resume(throwing: MapViewLoadAttemptFailure(
                error: error,
                shouldRetryAutomatically: classification.shouldRetryAutomatically))
        } else {
            failLoadImmediately(error)
        }
        decisionHandler(.cancel)
    }

    public func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url else { decisionHandler(.cancel); return }
        if url.scheme == "about" || isAllowed(url) { decisionHandler(.allow); return }
        // A new-window request (targetFrame == nil) is handled once, in createWebViewWith.
        if navigationAction.targetFrame?.isMainFrame == true {
            handleExternalLink(url)
        }
        decisionHandler(.cancel)
    }

    public func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if let url = navigationAction.request.url, !isAllowed(url) {
            handleExternalLink(url)
        }
        return nil
    }

    public func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        let error = makeLoadError(
            .otherNavigation,
            detail: "WKWebView content process terminated")
        if let loadContinuation {
            self.loadContinuation = nil
            loadContinuation.resume(throwing: MapViewLoadAttemptFailure(
                error: error, shouldRetryAutomatically: false))
        } else {
            emit(.loadState(.failed))
            emit(.error(error))
        }
    }

    private func finishLoadAttempt(with underlying: Error) {
        guard let loadContinuation else { return }
        let classification = loadFailureClassification(for: underlying)
        let error = makeLoadError(classification, detail: String(describing: underlying))
        self.loadContinuation = nil
        loadContinuation.resume(throwing: MapViewLoadAttemptFailure(
            error: error,
            shouldRetryAutomatically: classification.shouldRetryAutomatically))
    }

    private func handleNavigationFailure(_ navigation: WKNavigation?, error: Error) {
        if let activeNavigation, navigation === activeNavigation {
            self.activeNavigation = nil
            finishLoadAttempt(with: error)
            return
        }
        guard !isCancelledMetamapsNavigationError(error) else { return }
        let classification = loadFailureClassification(for: error)
        failLoadImmediately(makeLoadError(classification, detail: String(describing: error)))
    }

    private func failLoadImmediately(_ error: MetamapsError) {
        emit(.loadState(.failed))
        emit(.error(error))
    }

    private func loadFailureClassification(for error: Error) -> MapViewLoadFailureClassification {
        guard let code = (error as? URLError)?.code else { return .otherNavigation }
        switch code {
        case .appTransportSecurityRequiresSecureConnection,
             .secureConnectionFailed,
             .serverCertificateHasBadDate,
             .serverCertificateUntrusted,
             .serverCertificateHasUnknownRoot,
             .serverCertificateNotYetValid,
             .clientCertificateRejected,
             .clientCertificateRequired:
            return .tls
        case .timedOut,
             .cannotFindHost,
             .cannotConnectToHost,
             .dnsLookupFailed,
             .networkConnectionLost,
             .notConnectedToInternet,
             .internationalRoamingOff,
             .callIsActive,
             .dataNotAllowed:
            return .connection
        default:
            return .otherNavigation
        }
    }

    private func makeLoadError(
        _ classification: MapViewLoadFailureClassification,
        detail: String
    ) -> MetamapsError {
        .init(
            code: classification.code,
            message: "The embedded Metamaps map could not be loaded.",
            recoverable: classification.recoverable,
            userAction: classification.userAction,
            debugDetail: detail)
    }

    private func handleExternalLink(_ url: URL) {
        guard !disposed, defaultMetamapsExternalLinkDecision(for: url) != nil else { return }
        emit(.externalLinkRequested(url))
        let decision = delegate?.metamapsMapView(self, decideExternalLink: url) ?? .openDefault
        if decision == .openDefault {
            openExternalLink(url)
        }
    }

    private func topViewController(in root: UIViewController?) -> UIViewController? {
        if let presented = root?.presentedViewController {
            return topViewController(in: presented)
        }
        if let navigation = root as? UINavigationController {
            return topViewController(in: navigation.visibleViewController)
        }
        if let tab = root as? UITabBarController {
            return topViewController(in: tab.selectedViewController)
        }
        return root
    }
}

private struct MapViewLoadAttemptFailure: Error {
    let error: MetamapsError
    let shouldRetryAutomatically: Bool
}

@MainActor
private protocol ScriptMessageReceiver: AnyObject {
    func didReceiveScriptMessage(_ message: WKScriptMessage)
}

private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: (any ScriptMessageReceiver)?
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        Task { @MainActor [weak self] in self?.target?.didReceiveScriptMessage(message) }
    }
}
#endif
