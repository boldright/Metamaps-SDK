import 'package:flutter_test/flutter_test.dart';
import 'package:metamap_positioning/metamap_positioning.dart';
import 'package:metamap_positioning/src/map_view.dart';
import 'package:metamap_positioning/src/native_gateway.dart';

void main() {
  group('MapView creation parameters', () {
    test('maps every native MapView configuration field', () {
      final configuration = MetamapMapViewConfiguration(
        mapSlug: 'office',
        baseUrl: Uri.parse('https://example.metamaps.jp'),
        groupId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
        language: 'ja-JP',
        initialFloorId: '11111111-2222-4333-8444-555555555555',
        previewToken: 'pv1.token',
        positioningPolicy: MapViewPositioningPolicy.hostControlled,
        positioningStartTrigger: MapViewPositioningStartTrigger.automatic,
        showsBeaconDiagnostics: true,
        additionalQuery: const <String, String>{
          'campaign': 'summer',
          'lang': 'en',
        },
        retryPolicy: MetamapMapViewRetryPolicy.disabled,
        userAgentAppendix: 'ExampleApp/5.1',
        isWebViewInspectable: true,
      );

      expect(mapViewCreationParamsForTesting(configuration), <String, Object?>{
        'baseUrl': 'https://example.metamaps.jp',
        'mapSlug': 'office',
        'groupId': 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
        'language': 'ja-JP',
        'initialFloorId': '11111111-2222-4333-8444-555555555555',
        'previewToken': 'pv1.token',
        'positioningPolicy': 'hostControlled',
        'positioningStartTrigger': 'automatic',
        'showsBeaconDiagnostics': true,
        'additionalQuery': <String, String>{'campaign': 'summer', 'lang': 'en'},
        'retryPolicy': 'disabled',
        'userAgentAppendix': 'ExampleApp/5.1',
        'isWebViewInspectable': true,
      });
    });
  });

  group('MapView automatic positioning', () {
    test('defaults to positioning automatically once the OS permission is granted', () {
      final configuration = MetamapMapViewConfiguration(mapSlug: 'office');
      expect(
        configuration.positioningStartTrigger,
        MapViewPositioningStartTrigger.automaticWhenAuthorized,
      );
      expect(
        mapViewCreationParamsForTesting(configuration)['positioningStartTrigger'],
        'automaticWhenAuthorized',
      );
    });

    test('rejects an unknown trigger as configuration input', () {
      final configuration = MetamapMapViewConfiguration(
        mapSlug: 'office',
        positioningStartTrigger: MapViewPositioningStartTrigger.unknown,
      );
      expect(configuration.validate, throwsA(isA<MetamapPositioningError>()));
    });

    test('maps every wire value in both directions', () {
      for (final trigger in MapViewPositioningStartTrigger.values) {
        expect(
          MapViewPositioningStartTrigger.fromWire(trigger.wireValue),
          trigger,
        );
      }
      // A minor addition in the native SDK maps to unknown instead of crashing.
      expect(
        MapViewPositioningStartTrigger.fromWire('somethingNew'),
        MapViewPositioningStartTrigger.unknown,
      );
    });
  });

  group('MapView external links', () {
    test('opens by default when no host decision callback is configured', () {
      expect(
        mapViewExternalLinkOpensDefault(
          null,
          Uri.parse('https://example.com/path'),
        ),
        isTrue,
      );
    });

    test('preserves explicit handled and open-default decisions', () {
      final url = Uri.parse('https://example.com/path');

      expect(
        mapViewExternalLinkOpensDefault(
          (_) => MetamapExternalLinkDecision.handled,
          url,
        ),
        isFalse,
      );
      expect(
        mapViewExternalLinkOpensDefault(
          (_) => MetamapExternalLinkDecision.openDefault,
          url,
        ),
        isTrue,
      );
    });

    test('propagates callback failures so the native request stays closed', () {
      expect(
        () => mapViewExternalLinkOpensDefault(
          (_) => throw StateError('decision failed'),
          Uri.parse('https://example.com/path'),
        ),
        throwsStateError,
      );
    });

    test(
      'fails closed when the native request has no registered view',
      () async {
        expect(
          await NativeEventRouter.instance
              .decideMapViewExternalLinkOpensDefault(
                404,
                'https://example.com/path',
              ),
          isFalse,
        );
      },
    );
  });
}
