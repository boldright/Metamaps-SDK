import 'package:flutter_test/flutter_test.dart';
import 'package:metamaps_flutter/src/messages.g.dart';
import 'package:metamaps_flutter/src/native_gateway.dart';
import 'package:metamaps_flutter/metamaps_flutter.dart';

void main() {
  test(
    'maps the complete native estimate without dropping nullable values',
    () {
      final update = mapPositioningUpdate(
        NativePositioningUpdate(
          mapId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
          groupId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
          estimate: NativePositionEstimate(
            sequence: 42,
            monotonicTimestampMs: 12345,
            status: 'tracking',
            mode: 'ble_pdr_pf',
            floorId: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
            floorProbability: 0.94,
            local: NativeLocalPosition(x: 1.25, y: 0.0, z: -3.5),
            accuracyRadiusM: 2.1,
            headingDeg: 91.5,
            headingAccuracyDeg: 8.0,
            speedMps: 1.2,
            freshBeaconCount: 7,
            lastBleAgeMs: 320,
            manifestRevision: 9,
            algorithmVersion: '1.0.0',
            stale: false,
            diagnosticFlags: <String>['motion_full'],
          ),
          wgs84: NativeWgs84Position(
            longitude: 139.767,
            latitude: 35.681,
            elevationM: 12.5,
          ),
        ),
      );

      expect(update.estimate.sequence, 42);
      expect(
        update.estimate.monotonicTimestamp,
        const Duration(milliseconds: 12345),
      );
      expect(update.estimate.status, PositioningLifecycleStatus.tracking);
      expect(update.estimate.mode, PositioningMode.blePdrPf);
      expect(update.estimate.local?.z, -3.5);
      expect(update.estimate.headingDeg, 91.5);
      expect(update.estimate.lastBleAge, const Duration(milliseconds: 320));
      expect(update.estimate.diagnosticFlags, <String>['motion_full']);
      expect(update.wgs84?.longitude, 139.767);
      expect(update.wgs84?.elevationM, 12.5);
    },
  );

  test('maps unknown native enums to public unknown cases', () {
    final report = mapCapabilityReport(
      NativeCapabilityReport(
        platform: 'future',
        osVersion: '1',
        sdkVersion: '2',
        ble: NativeBleCapabilities(
          supported: true,
          enabled: true,
          rangingAvailable: true,
          foregroundScan: true,
          backgroundScan: 'future',
          directionFinding: 'future',
          channelSounding: 'future',
        ),
        authorization: NativeAuthorizationCapabilities(
          location: 'future',
          preciseLocation: true,
          bluetoothScan: 'future',
          motion: 'future',
        ),
        sensors: NativeSensorCapabilities(
          stepDetector: true,
          rotationVector: true,
          gyroscope: true,
          magnetometer: true,
          barometer: true,
        ),
        selectedProfile: 'future',
      ),
    );

    expect(report.authorization.location, LocationAuthorizationState.unknown);
    expect(report.authorization.motion, MotionAuthorizationState.unknown);
    expect(report.selectedProfile, PositioningMode.unknown);
  });

  test('maps motion and beacon diagnostic events without dropping fields', () {
    final heading = mapPositioningEvent(
      NativePositioningEvent(
        clientId: 1,
        type: 'motionHeading',
        motionHeading: NativeMotionHeadingReading(
          localHeadingDeg: 92,
          magneticFieldAccuracy: 'high',
        ),
      ),
    );
    final beacon = mapPositioningEvent(
      NativePositioningEvent(
        clientId: 1,
        type: 'beaconSignals',
        beaconSignals: <NativeBeaconSignalReading>[
          NativeBeaconSignalReading(
            beaconId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
            uuid: 'fda50693-a4e2-4fb1-afcf-c6eb07647825',
            major: 100,
            minor: 1,
            floorId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
            rssiDbm: -59,
            smoothedRssiDbm: -60,
            radioDistanceM: 1.1,
            configuredPosition: NativeLocalPosition(x: 1, y: 2, z: 3),
            configuredWgs84: NativeWgs84Position(
              longitude: 139,
              latitude: 35,
              elevationM: 2,
            ),
            monotonicTimestampMs: 1234,
          ),
        ],
      ),
    );

    expect(
      (heading as MotionHeadingEvent).reading.magneticFieldAccuracy,
      'high',
    );
    final reading = (beacon as BeaconSignalsEvent).readings.single;
    expect(reading.radioDistanceM, 1.1);
    expect(reading.configuredWgs84.elevationM, 2);
    expect(reading.monotonicTimestamp, const Duration(milliseconds: 1234));
  });
}
