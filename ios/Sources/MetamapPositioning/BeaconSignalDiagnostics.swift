import Foundation
import MetamapPositioningCore

/// Keeps BLE diagnostic smoothing and coordinate conversion off the main actor.
///
/// The web map updates its scene on the render thread, so only sendable values are computed here.
actor BeaconSignalDiagnosticsWorker {
    /// Core Location can split registered beacon regions across adjacent ranging callbacks.
    /// Retaining the latest observation briefly lets the operator picker show every beacon that
    /// was actually heard, rather than only the final callback-sized subset.
    private static let snapshotRetentionMs: Int64 = 15_000
    private var diagnostics = BeaconSignalDiagnostics()
    private var latestByBeaconId: [UUID: BeaconSignalReading] = [:]

    func readings(
        observations: [BeaconObservation],
        manifest: PositioningManifest,
        latestUpdate: PositioningUpdate?
    ) throws -> [BeaconSignalReading] {
        let incoming = try diagnostics.readings(
            observations: observations,
            manifest: manifest,
            latestUpdate: latestUpdate)
        incoming.forEach { latestByBeaconId[$0.beaconId] = $0 }
        let now = observations.map(\.monotonicTimestampMs).max()
            ?? latestByBeaconId.values.map(\.monotonicTimestampMs).max()
            ?? 0
        latestByBeaconId = latestByBeaconId.filter {
            now - $0.value.monotonicTimestampMs <= Self.snapshotRetentionMs
        }
        return latestByBeaconId.values.sorted {
            if $0.rssiDbm != $1.rssiDbm { return $0.rssiDbm > $1.rssiDbm }
            if $0.major != $1.major { return $0.major < $1.major }
            return $0.minor < $1.minor
        }
    }
}

struct BeaconSignalDiagnostics {
    private static let smoothingAlpha = 0.35
    private var smoothedRssiByKey: [String: Double] = [:]

    mutating func reset() {
        smoothedRssiByKey.removeAll()
    }

    mutating func readings(
        observations: [BeaconObservation],
        manifest: PositioningManifest,
        latestUpdate: PositioningUpdate?
    ) throws -> [BeaconSignalReading] {
        let beaconsByKey = Dictionary(
            uniqueKeysWithValues: manifest.beacons.filter(\.isEnabled).map { ($0.key, $0) })
        var values: [BeaconSignalReading] = []

        for observation in observations where observation.rssiDbm.isFinite && observation.rssiDbm < 0 {
            guard let beacon = beaconsByKey[observation.beaconKey.lowercased()],
                  beacon.position.count >= 3 else { continue }

            let previous = smoothedRssiByKey[beacon.key] ?? observation.rssiDbm
            let smoothed = previous
                + Self.smoothingAlpha * (observation.rssiDbm - previous)
            smoothedRssiByKey[beacon.key] = smoothed

            let calibratedRssi = beacon.calibratedRssiAt1mDbm
                ?? beacon.advertisedMeasuredPowerDbm
                ?? manifest.algorithm.parameters.defaultCalibratedRssiAt1mDbm
            let pathLoss = beacon.pathLossExponent
                ?? manifest.algorithm.parameters.defaultPathLossExponent
            let radioDistance = Self.radioDistanceM(
                rssiDbm: smoothed,
                calibratedRssiAt1mDbm: calibratedRssi,
                pathLossExponent: pathLoss)
            let configuredWgs84 = Wgs84Position(
                longitude: beacon.position[0],
                latitude: beacon.position[1],
                elevationM: beacon.position[2])
            let configured = try PositioningCoordinateTransform.wgs84ToLocal(
                configuredWgs84,
                frame: manifest.coordinateFrame)
            let devicePosition = latestUpdate?.estimate.floorId == beacon.floorId
                ? latestUpdate?.estimate.local
                : nil
            let deviceWgs84 = latestUpdate?.estimate.floorId == beacon.floorId
                ? latestUpdate?.wgs84
                : nil
            let configuredDistance = devicePosition.map {
                hypot(configured.x - $0.x, configured.z - $0.z)
            }

            values.append(.init(
                beaconId: beacon.id,
                uuid: observation.uuid.lowercased(),
                major: observation.major,
                minor: observation.minor,
                floorId: beacon.floorId,
                rssiDbm: observation.rssiDbm,
                smoothedRssiDbm: smoothed,
                radioDistanceM: radioDistance,
                configuredPosition: configured,
                configuredWgs84: configuredWgs84,
                devicePosition: devicePosition,
                deviceWgs84: deviceWgs84,
                configuredDistanceM: configuredDistance,
                distanceDeltaM: configuredDistance.map { radioDistance - $0 },
                monotonicTimestampMs: observation.monotonicTimestampMs))
        }

        return values.sorted {
            if $0.rssiDbm != $1.rssiDbm { return $0.rssiDbm > $1.rssiDbm }
            if $0.major != $1.major { return $0.major < $1.major }
            return $0.minor < $1.minor
        }
    }

    static func radioDistanceM(
        rssiDbm: Double,
        calibratedRssiAt1mDbm: Double,
        pathLossExponent: Double
    ) -> Double {
        guard rssiDbm.isFinite,
              calibratedRssiAt1mDbm.isFinite,
              pathLossExponent.isFinite,
              pathLossExponent > 0 else { return 100 }
        return min(100, max(0.1, pow(10, (calibratedRssiAt1mDbm - rssiDbm) / (10 * pathLossExponent))))
    }
}
