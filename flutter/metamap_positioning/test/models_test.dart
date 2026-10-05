import 'package:flutter_test/flutter_test.dart';
import 'package:metamap_positioning/metamap_positioning.dart';

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
        MetamapPositioningErrorCode.fromWire('future_error'),
        MetamapPositioningErrorCode.unknown,
      );
      expect(
        MapViewPositioningPolicy.fromWire('future_policy'),
        MapViewPositioningPolicy.unknown,
      );
      expect(
        MetamapMapViewRetryPolicy.fromWire('future_retry'),
        MetamapMapViewRetryPolicy.unknown,
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
      MetamapMapViewConfiguration(
        mapSlug: 'office',
        language: 'ja-JP',
        initialFloorId: 'aaaaaaaa-bbbb-8ccc-dddd-eeeeeeeeeeee',
        previewToken: 'pv1.abc-123_def.456',
      ).validate();
    });

    test('rejects credentials, unknown input enums, and invalid slugs', () {
      expect(
        () => PositioningConfiguration(mapSlug: 'bad slug').validate(),
        throwsA(isA<MetamapPositioningError>()),
      );
      expect(
        () => PositioningConfiguration(
          mapSlug: 'office',
          baseUrl: Uri.parse('https://user:secret@metamaps.jp'),
        ).validate(),
        throwsA(isA<MetamapPositioningError>()),
      );
      expect(
        () => MetamapMapViewConfiguration(
          mapSlug: 'office',
          positioningPolicy: MapViewPositioningPolicy.unknown,
        ).validate(),
        throwsA(isA<MetamapPositioningError>()),
      );
      expect(
        () => MetamapMapViewConfiguration(
          mapSlug: 'office',
          retryPolicy: MetamapMapViewRetryPolicy.unknown,
        ).validate(),
        throwsA(isA<MetamapPositioningError>()),
      );
      expect(
        () => MetamapMapViewConfiguration(
          mapSlug: 'office',
          initialFloorId: 'not-a-uuid',
        ).validate(),
        throwsA(isA<MetamapPositioningError>()),
      );
      expect(
        () => MetamapMapViewConfiguration(
          mapSlug: 'office',
          previewToken: 'bad token!',
        ).validate(),
        throwsA(isA<MetamapPositioningError>()),
      );
    });
  });

  test('MapView events preserve new and legacy Spot StableKeys as opaque values', () {
    expect(const SpotSelectedEvent(spotId: '6Y6SSBY5').spotId, '6Y6SSBY5');
    expect(
      const RouteChangedEvent(
        destinationSpotId: 'spot_6Y6SSBY5',
        active: true,
      ).destinationSpotId,
      'spot_6Y6SSBY5',
    );
  });
}
