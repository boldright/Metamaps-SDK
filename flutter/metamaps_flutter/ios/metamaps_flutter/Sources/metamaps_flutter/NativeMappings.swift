import Foundation
import Metamaps
import MetamapsPositioning
import MetamapsPositioningCore

extension CapabilityReport {
  func flutterValue() -> NativeCapabilityReport {
    NativeCapabilityReport(
      platform: platform,
      osVersion: osVersion,
      sdkVersion: sdkVersion,
      ble: NativeBleCapabilities(
        supported: ble.supported,
        enabled: ble.enabled,
        rangingAvailable: ble.rangingAvailable,
        foregroundScan: ble.foregroundScan,
        backgroundScan: ble.backgroundScan,
        directionFinding: ble.directionFinding,
        channelSounding: ble.channelSounding
      ),
      authorization: NativeAuthorizationCapabilities(
        location: authorization.location.rawValue,
        preciseLocation: authorization.preciseLocation,
        bluetoothScan: authorization.bluetoothScan,
        motion: authorization.motion.rawValue
      ),
      sensors: NativeSensorCapabilities(
        stepDetector: sensors.stepDetector,
        rotationVector: sensors.rotationVector,
        gyroscope: sensors.gyroscope,
        magnetometer: sensors.magnetometer,
        barometer: sensors.barometer
      ),
      selectedProfile: selectedProfile
    )
  }
}

extension PositioningUpdate {
  func flutterValue() -> NativePositioningUpdate {
    NativePositioningUpdate(
      mapId: mapId.uuidString.lowercased(),
      groupId: groupId.uuidString.lowercased(),
      estimate: estimate.flutterValue(),
      wgs84: wgs84.map {
        NativeWgs84Position(
          longitude: $0.longitude,
          latitude: $0.latitude,
          elevationM: $0.elevationM
        )
      }
    )
  }
}

private extension PositionEstimate {
  func flutterValue() -> NativePositionEstimate {
    NativePositionEstimate(
      sequence: sequence,
      monotonicTimestampMs: monotonicTimestampMs,
      status: status,
      mode: mode,
      floorId: floorId?.uuidString.lowercased(),
      floorProbability: floorProbability,
      local: local.map { NativeLocalPosition(x: $0.x, y: $0.y, z: $0.z) },
      accuracyRadiusM: accuracyRadiusM,
      headingDeg: headingDeg,
      headingAccuracyDeg: headingAccuracyDeg,
      speedMps: speedMps,
      freshBeaconCount: Int64(freshBeaconCount),
      lastBleAgeMs: lastBleAgeMs,
      manifestRevision: Int64(manifestRevision),
      algorithmVersion: algorithmVersion,
      stale: stale,
      diagnosticFlags: diagnosticFlags
    )
  }
}

private extension LocalPosition {
  func flutterValue() -> NativeLocalPosition {
    NativeLocalPosition(x: x, y: y, z: z)
  }
}

private extension Wgs84Position {
  func flutterValue() -> NativeWgs84Position {
    NativeWgs84Position(
      longitude: longitude,
      latitude: latitude,
      elevationM: elevationM
    )
  }
}

private extension MotionHeadingReading {
  func flutterValue() -> NativeMotionHeadingReading {
    NativeMotionHeadingReading(
      localHeadingDeg: localHeadingDeg,
      magneticFieldAccuracy: magneticFieldAccuracy
    )
  }
}

private extension BeaconSignalReading {
  func flutterValue() -> NativeBeaconSignalReading {
    NativeBeaconSignalReading(
      beaconId: beaconId.uuidString.lowercased(),
      uuid: uuid,
      major: Int64(major),
      minor: Int64(minor),
      floorId: floorId.uuidString.lowercased(),
      rssiDbm: rssiDbm,
      smoothedRssiDbm: smoothedRssiDbm,
      radioDistanceM: radioDistanceM,
      configuredPosition: configuredPosition.flutterValue(),
      configuredWgs84: configuredWgs84.flutterValue(),
      devicePosition: devicePosition?.flutterValue(),
      deviceWgs84: deviceWgs84?.flutterValue(),
      configuredDistanceM: configuredDistanceM,
      distanceDeltaM: distanceDeltaM,
      monotonicTimestampMs: monotonicTimestampMs
    )
  }
}

extension Error {
  func flutterValue() -> NativeErrorMessage {
    if let typed = self as? MetamapsError {
      return NativeErrorMessage(
        code: typed.code.rawValue,
        message: typed.message,
        recoverable: typed.recoverable,
        userAction: typed.userAction.rawValue,
        debugDetail: typed.debugDetail
      )
    }
    return NativeErrorMessage(
      code: "internalInvariantViolation",
      message: "The native positioning integration failed.",
      recoverable: false,
      userAction: "retry",
      debugDetail: String(describing: self)
    )
  }
}

extension MetamapsPositioningEvent {
  func flutterValue(clientId: Int64) -> NativePositioningEvent {
    switch self {
    case .position(let update):
      return NativePositioningEvent(
        clientId: clientId,
        type: "position",
        update: update.flutterValue()
      )
    case .beaconSignals(let readings):
      return NativePositioningEvent(
        clientId: clientId,
        type: "beaconSignals",
        beaconSignals: readings.map { $0.flutterValue() }
      )
    case .motionHeading(let reading):
      return NativePositioningEvent(
        clientId: clientId,
        type: "motionHeading",
        motionHeading: reading.flutterValue()
      )
    case .status(let status):
      return NativePositioningEvent(
        clientId: clientId,
        type: "status",
        status: status.rawValue
      )
    case .capabilities(let report):
      return NativePositioningEvent(
        clientId: clientId,
        type: "capabilities",
        capabilities: report.flutterValue()
      )
    case .error(let error):
      return NativePositioningEvent(
        clientId: clientId,
        type: "error",
        error: error.flutterValue()
      )
    }
  }
}

extension MetamapsMapViewEvent {
  func flutterValue(viewId: Int64) -> NativeMapViewEvent {
    switch self {
    case .ready(let event):
      return NativeMapViewEvent(
        viewId: viewId,
        type: "ready",
        ready: NativeMapReadyEvent(
          mapId: event.mapId.uuidString.lowercased(),
          groupId: event.groupId.uuidString.lowercased(),
          configRevision: event.configRevision,
          manifestRevision: event.manifestRevision.map(Int64.init)
        )
      )
    case .loadState(let state):
      return NativeMapViewEvent(
        viewId: viewId,
        type: "loadState",
        loadState: state.rawValue
      )
    case .positioningStatus(let status):
      return NativeMapViewEvent(
        viewId: viewId,
        type: "positioningStatus",
        positioningStatus: status.rawValue
      )
    case .capabilities(let report):
      return NativeMapViewEvent(
        viewId: viewId,
        type: "capabilities",
        capabilities: report.flutterValue()
      )
    case .motionHeading(let reading):
      return NativeMapViewEvent(
        viewId: viewId,
        type: "motionHeading",
        motionHeading: reading.flutterValue()
      )
    case .position(let update):
      return NativeMapViewEvent(
        viewId: viewId,
        type: "position",
        position: update.flutterValue()
      )
    case .beaconSignals(let readings):
      return NativeMapViewEvent(
        viewId: viewId,
        type: "beaconSignals",
        beaconSignals: readings.map { $0.flutterValue() }
      )
    case .positioningAuthorizationRequested:
      return NativeMapViewEvent(
        viewId: viewId,
        type: "positioningAuthorizationRequested"
      )
    case .positioningStartRequested:
      return NativeMapViewEvent(viewId: viewId, type: "positioningStartRequested")
    case .floorChanged(let event):
      return NativeMapViewEvent(
        viewId: viewId,
        type: "floorChanged",
        floorChanged: NativeFloorChangedEvent(
          floorId: event.floorId?.uuidString.lowercased(),
          source: event.source
        )
      )
    case .spotSelected(let event):
      return NativeMapViewEvent(
        viewId: viewId,
        type: "spotSelected",
        spotSelected: NativeSpotSelectedEvent(
          spotId: event.spotId
        )
      )
    case .routeChanged(let event):
      return NativeMapViewEvent(
        viewId: viewId,
        type: "routeChanged",
        routeChanged: NativeRouteChangedEvent(
          destinationSpotId: event.destinationSpotId,
          active: event.active
        )
      )
    case .externalLinkRequested(let url):
      return NativeMapViewEvent(
        viewId: viewId,
        type: "externalLinkRequested",
        externalUrl: url.absoluteString
      )
    case .error(let error):
      return NativeMapViewEvent(
        viewId: viewId,
        type: "error",
        error: error.flutterValue()
      )
    }
  }
}
