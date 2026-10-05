import Metamaps
import UIKit

final class MetamapsMapViewController: UIViewController, MetamapsMapViewDelegate {
    private let mapView = MetamapsMapView(configuration: .init(
        mapSlug: "example",
        language: "ja"
    ))

    override func viewDidLoad() {
        super.viewDidLoad()
        mapView.delegate = self
        mapView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(mapView)
        NSLayoutConstraint.activate([
            mapView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            mapView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            mapView.topAnchor.constraint(equalTo: view.topAnchor),
            mapView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        Task {
            do { try await mapView.load() }
            catch { presentLoadError(error) }
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isMovingFromParent || isBeingDismissed || navigationController?.isBeingDismissed == true {
            mapView.dispose()
        }
    }

    func metamapsMapView(_ mapView: MetamapsMapView, didReceive event: MetamapsMapViewEvent) {
        switch event {
        case .externalLinkRequested(let url):
            // Validate against the host app's allow-list before opening.
            print("Open externally: \(url)")
        case .error(let error):
            print("Metamaps SDK error: \(error.code.rawValue)")
        default:
            // Minor SDK updates may add events. Always keep a default branch.
            break
        }
    }

    func metamapsMapView(
        _ mapView: MetamapsMapView,
        decideExternalLink url: URL
    ) -> MetamapsExternalLinkDecision {
        if url.scheme?.lowercased() == "mailto" {
            UIApplication.shared.open(url)
            return .handled
        }
        return .openDefault
    }

    private func presentLoadError(_ error: Error) {
        let alert = UIAlertController(title: "Could not load the map", message: error.localizedDescription,
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Close", style: .cancel))
        present(alert, animated: true)
    }
}
