import Metamaps
import SwiftUI

struct MetamapsMapScreen: UIViewRepresentable {
    let configuration: MetamapsMapViewConfiguration
    let onEvent: @MainActor (MetamapsMapViewEvent) -> Void
    let onLoadError: @MainActor (Error) -> Void

    init(
        mapSlug: String,
        onEvent: @escaping @MainActor (MetamapsMapViewEvent) -> Void = { _ in },
        onLoadError: @escaping @MainActor (Error) -> Void = { _ in }
    ) {
        configuration = .init(
            mapSlug: mapSlug,
            language: "ja"
        )
        self.onEvent = onEvent
        self.onLoadError = onLoadError
    }

    init(
        configuration: MetamapsMapViewConfiguration,
        onEvent: @escaping @MainActor (MetamapsMapViewEvent) -> Void = { _ in },
        onLoadError: @escaping @MainActor (Error) -> Void = { _ in }
    ) {
        self.configuration = configuration
        self.onEvent = onEvent
        self.onLoadError = onLoadError
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onEvent: onEvent, onLoadError: onLoadError)
    }

    func makeUIView(context: Context) -> MetamapsMapView {
        let view = MetamapsMapView(configuration: configuration)
        view.delegate = context.coordinator
        Task {
            do { try await view.load() }
            catch { context.coordinator.onLoadError(error) }
        }
        return view
    }

    func updateUIView(_ uiView: MetamapsMapView, context: Context) {}

    static func dismantleUIView(_ uiView: MetamapsMapView, coordinator: Coordinator) {
        uiView.dispose()
    }

    @MainActor
    final class Coordinator: MetamapsMapViewDelegate {
        private let onEvent: @MainActor (MetamapsMapViewEvent) -> Void
        private let loadErrorHandler: @MainActor (Error) -> Void

        init(
            onEvent: @escaping @MainActor (MetamapsMapViewEvent) -> Void,
            onLoadError: @escaping @MainActor (Error) -> Void
        ) {
            self.onEvent = onEvent
            loadErrorHandler = onLoadError
        }

        func metamapsMapView(_ mapView: MetamapsMapView, didReceive event: MetamapsMapViewEvent) {
            switch event {
            case .error(let error):
                print("Metamaps SDK error: \(error.code.rawValue) detail=\(error.debugDetail ?? "-")")
            default:
                // Minor SDK updates may add events. Always keep a default branch.
                break
            }
            onEvent(event)
        }

        func metamapsMapView(
            _ mapView: MetamapsMapView,
            decideExternalLink url: URL
        ) -> MetamapsExternalLinkDecision {
            // Return .handled after presenting host-owned confirmation/UI.
            .openDefault
        }

        func onLoadError(_ error: Error) {
            // Production apps should route this to their own persistent error UI.
            print("Metamaps load failed: \(error)")
            loadErrorHandler(error)
        }
    }
}
