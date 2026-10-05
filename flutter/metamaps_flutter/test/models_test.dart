import 'package:flutter_test/flutter_test.dart';
import 'package:metamaps_flutter/metamaps_flutter.dart';

void main() {
  group('wire enums', () {
    test('preserve known values and safely map future values', () {
      expect(
        PositioningLifecycleStatus.fromWire('tracking'),
        PositioningLifecycleStatus.tracking,
      );
      expect(
        PositioningLifecycleStatus.fromWire('future_state'),
        PositioningLifecycleStatus.unknown,
      );
      expect(
        MetamapsErrorCode.fromWire('future_error'),
        MetamapsErrorCode.unknown,
      );
      expect(
        MapViewPositioningPolicy.fromWire('future_policy'),
        MapViewPositioningPolicy.unknown,
      );
      expect(
        MetamapsMapViewRetryPolicy.fromWire('future_retry'),
        MetamapsMapViewRetryPolicy.unknown,
      );
    });
  });

  group('configuration validation', () {
    test('accepts production and localhost HTTPS/HTTP contracts', () {
      PositioningConfiguration(mapSlug: 'office_1').validate();
      PositioningConfiguration(
        mapSlug: 'office',
        baseUrl: Uri.parse('http://127.0.0.1:5001'),
        groupId: '00000000-0000-0000-0000-000000000000',
      ).validate();
      MetamapsMapViewConfiguration(
        mapSlug: 'office',
        language: 'ja-JP',
        initialFloorId: 'aaaaaaaa-bbbb-8ccc-dddd-eeeeeeeeeeee',
        previewToken: 'pv1.abc-123_def.456',
      ).validate();
    });

    test('rejects credentials, unknown input enums, and invalid slugs', () {
      expect(
        () => PositioningConfiguration(mapSlug: 'bad slug').validate(),
        throwsA(isA<MetamapsError>()),
      );
      expect(
        () => PositioningConfiguration(
          mapSlug: 'office',
          baseUrl: Uri.parse('https://user:secret@metamaps.jp'),
        ).validate(),
        throwsA(isA<MetamapsError>()),
      );
      expect(
        () => MetamapsMapViewConfiguration(
          mapSlug: 'office',
          positioningPolicy: MapViewPositioningPolicy.unknown,
        ).validate(),
        throwsA(isA<MetamapsError>()),
      );
      expect(
        () => MetamapsMapViewConfiguration(
          mapSlug: 'office',
          retryPolicy: MetamapsMapViewRetryPolicy.unknown,
        ).validate(),
        throwsA(isA<MetamapsError>()),
      );
      expect(
        () => MetamapsMapViewConfiguration(
          mapSlug: 'office',
          initialFloorId: 'not-a-uuid',
        ).validate(),
        throwsA(isA<MetamapsError>()),
      );
      expect(
        () => MetamapsMapViewConfiguration(
          mapSlug: 'office',
          previewToken: 'bad token!',
        ).validate(),
        throwsA(isA<MetamapsError>()),
      );
    });
  });

  test(
    'MapView events preserve new and legacy Spot StableKeys as opaque values',
    () {
      expect(const SpotSelectedEvent(spotId: '6Y6SSBY5').spotId, '6Y6SSBY5');
      expect(
        const RouteChangedEvent(
          destinationSpotId: 'spot_6Y6SSBY5',
          active: true,
        ).destinationSpotId,
        'spot_6Y6SSBY5',
      );
    },
  );
}
