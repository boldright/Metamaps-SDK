import Foundation
import Testing
import MetamapsPositioning
@_spi(MetamapsInternal) @testable import Metamaps

struct BridgeProtocolTests {
    @Test func handshakePinsMapGroupAndSequence() throws {
        let mapId = UUID(), groupId = UUID()
        var validator = BridgeValidator(requestedGroupId: groupId)
        let ready = try validator.validate(.init(
            type: "map.ready", mapId: mapId, groupId: groupId, sequence: 1,
            payload: .object(["configRevision": .string("42"), "manifestRevision": .number(7)])) )
        #expect(ready == .init(mapId: mapId, groupId: groupId, configRevision: "42", manifestRevision: 7))
        #expect(!validator.handshakeComplete)
        #expect(validator.completeHandshake(with: try #require(ready)))
        #expect(validator.handshakeComplete)
        _ = try validator.validate(.init(type: "map.floorChanged", mapId: mapId, groupId: groupId, sequence: 2))
        #expect(throws: Error.self) {
            _ = try validator.validate(.init(type: "map.floorChanged", mapId: mapId, groupId: groupId, sequence: 2))
        }
        #expect(throws: Error.self) {
            _ = try validator.validate(.init(type: "map.ready", mapId: UUID(), groupId: groupId, sequence: 3))
        }
        validator.reset()
        #expect(!validator.handshakeComplete)
    }

    @Test func rejectsAnotherGroup() {
        var validator = BridgeValidator(requestedGroupId: UUID())
        #expect(throws: Error.self) {
            _ = try validator.validate(.init(type: "map.ready", mapId: UUID(), groupId: UUID(), sequence: 1))
        }
    }

    @Test func rejectsMalformedBridgeMinor() {
        var validator = BridgeValidator(requestedGroupId: nil)
        #expect(throws: Error.self) {
            _ = try validator.validate(.init(
                type: "map.ready", mapId: UUID(), groupId: UUID(), sequence: 0,
                bridgeVersion: "1.invalid"))
        }
    }

    @Test func bleTestModeAddsDedicatedURLFlagWithoutChangingStandardURLs() throws {
        let baseURL = URL(string: "https://metamaps.jp")!
        let standard = try makeMetamapsMapURL(configuration: .init(
            mapSlug: "office",
            baseURL: baseURL,
            language: "ja"
        ))
        let bleTest = try makeMetamapsMapURL(configuration: .init(
            mapSlug: "office",
            baseURL: baseURL,
            language: "ja",
            bleTest: true
        ))
        #expect(URLComponents(url: standard, resolvingAgainstBaseURL: false)?
            .queryItems?.contains(where: { $0.name == "bletest" }) == false)
        #expect(URLComponents(url: bleTest, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "bletest" })?.value == "1")
    }

    @Test func beaconDiagnosticsAreOptInForStandardMapAndAutomaticForBleTest() {
        let standard = MetamapsMapViewConfiguration(mapSlug: "office")
        let productionDiagnostic = MetamapsMapViewConfiguration(
            mapSlug: "office",
            showsBeaconDiagnostics: true)
        let bleTest = MetamapsMapViewConfiguration(
            mapSlug: "office",
            bleTest: true)

        #expect(!standard.effectiveBeaconDiagnostics)
        #expect(productionDiagnostic.effectiveBeaconDiagnostics)
        #expect(bleTest.effectiveBeaconDiagnostics)
        #expect(!standard.positioningConfiguration.beaconDiagnosticsEnabled)
        #expect(productionDiagnostic.positioningConfiguration.beaconDiagnosticsEnabled)
    }

    @Test func mapErrorIsARecoverableOperationFailureWithTheRuntimeCode() {
        let notFound = BridgeEnvelope(type: "map.error", sequence: 1, payload: .object([
            "code": .string("spot_not_found"),
            "message": .string("The requested map operation could not be completed."),
        ]))
        let malformed = BridgeEnvelope(type: "map.error", sequence: 2, payload: .object(["code": .string("<b>x</b>")]))

        // Not a load failure: a host that reloads on webContentLoadFailed must not reload for an unknown spot.
        let error = notFound.mapOperationError
        #expect(error.code == .mapOperationFailed)
        #expect(error.recoverable)
        #expect(error.userAction == MetamapsUserAction.none)
        #expect(error.debugDetail == "map.error: spot_not_found")
        #expect(malformed.mapErrorCode == "unknown")
        #expect(BridgeEnvelope(type: "map.error", sequence: 3).mapErrorCode == "unknown")
    }

    @Test func motionHeadingBridgePayloadKeepsLocalHeadingAndAccuracy() throws {
        let payload = try BridgeJSONValue.encode(MotionHeadingReading(
            localHeadingDeg: 62,
            magneticFieldAccuracy: "high"))
        #expect(payload == .object([
            "localHeadingDeg": .number(62),
            "magneticFieldAccuracy": .string("high"),
        ]))
    }
}
