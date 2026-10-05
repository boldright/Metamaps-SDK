import Metamap
import SwiftUI

struct MetamapMapScreen: UIViewRepresentable {
    let configuration: MetamapMapViewConfiguration
    let onEvent: @MainActor (MetamapMapViewEvent) -> Void
    let onLoadError: @MainActor (Error) -> Void

    init(
        mapSlug: String,
        onEvent: @escaping @MainActor (MetamapMapViewEvent) -> Void = { _ in },
        onLoadError: @escaping @MainActor (Error) -> Void = { _ in }
    ) {
        configuration = .init(
            mapSlug: mapSlug,
            language: "ja",
            positioningPolicy: .userInitiated
        )
        self.onEvent = onEvent
        self.onLoadError = onLoadError
    }

    init(
        configuration: MetamapMapViewConfiguration,
        onEvent: @escaping @MainActor (MetamapMapViewEvent) -> Void = { _ in },
        onLoadError: @escaping @MainActor (Error) -> Void = { _ in }
    ) {
        self.configuration = configuration
        self.onEvent = onEvent
        self.onLoadError = onLoadError
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onEvent: onEvent, onLoadError: onLoadError)
    }

    func makeUIView(context: Context) -> MetamapMapView {
        let view = MetamapMapView(configuration: configuration)
        view.delegate = context.coordinator
        Task {
            do { try await view.load() }
            catch { context.coordinator.onLoadError(error) }
        }
        return view
    }

    func updateUIView(_ uiView: MetamapMapView, context: Context) {}

    static func dismantleUIView(_ uiView: MetamapMapView, coordinator: Coordinator) {
        uiView.dispose()
    }

    @MainActor
    final class Coordinator: MetamapMapViewDelegate {
        private let onEvent: @MainActor (MetamapMapViewEvent) -> Void
        private let loadErrorHandler: @MainActor (Error) -> Void

        init(
            onEvent: @escaping @MainActor (MetamapMapViewEvent) -> Void,
            onLoadError: @escaping @MainActor (Error) -> Void
        ) {
            self.onEvent = onEvent
            loadErrorHandler = onLoadError
        }

        func metamapMapView(_ mapView: MetamapMapView, didReceive event: MetamapMapViewEvent) {
            switch event {
            case .error(let error):
                print("Metamaps SDK error: \(error.code.rawValue) detail=\(error.debugDetail ?? "-")")
            default:
                // Minor SDK updates may add events. Always keep a default branch.
                break
            }
            onEvent(event)
        }

        func metamapMapView(
            _ mapView: MetamapMapView,
            decideExternalLink url: URL
        ) -> MetamapExternalLinkDecision {
            // Return .handled after presenting host-owned confirmation/UI.
            .openDefault
        }

        func onLoadError(_ error: Error) {
            // Production apps should route this to their own persistent error UI.
            print("Metamap load failed: \(error)")
            loadErrorHandler(error)
        }
    }
}
