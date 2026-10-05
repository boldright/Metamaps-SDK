import Foundation
import MetamapsPositioning
import Testing
@_spi(MetamapsInternal) @testable import Metamaps

// Guards against HTTP response codes, recoverability, or automatic retry eligibility drifting from the specification table.
struct MapViewHTTPStatusClassificationTests {
    @Test func classifiesSuccessfulAndSpecifiedFailureStatuses() {
        #expect(MapViewLoadFailureClassification.httpStatus(399) == nil)
        #expect(MapViewLoadFailureClassification.httpStatus(404) == .init(
            code: .mapNotFound,
            recoverable: false,
            userAction: .checkConfiguration,
            shouldRetryAutomatically: false))
        #expect(MapViewLoadFailureClassification.httpStatus(401) == .init(
            code: .mapAccessDenied,
            recoverable: true,
            userAction: .retry,
            shouldRetryAutomatically: false))
        #expect(MapViewLoadFailureClassification.httpStatus(408)?.shouldRetryAutomatically == true)
        #expect(MapViewLoadFailureClassification.httpStatus(429)?.shouldRetryAutomatically == true)
        #expect(MapViewLoadFailureClassification.httpStatus(503)?.shouldRetryAutomatically == true)
        #expect(MapViewLoadFailureClassification.httpStatus(422)?.shouldRetryAutomatically == false)
    }

    @Test func classifiesConnectionAndTLSFailures() {
        #expect(MapViewLoadFailureClassification.connection.shouldRetryAutomatically)
        #expect(MapViewLoadFailureClassification.tls == .init(
            code: .webContentLoadFailed,
            recoverable: false,
            userAction: .checkConfiguration,
            shouldRetryAutomatically: false))
    }
}

// Guards against the automatic retry backoff changing from 1 second, 2 seconds, then 4 seconds.
struct MapViewRetryPlanTests {
    @Test func delayCapsAtFourSeconds() {
        #expect((1...7).map(MapViewRetryPlan.delaySeconds) == [1, 2, 4, 4, 4, 4, 4])
    }
}

struct MapViewCancelledNavigationErrorTests {
    @Test func recognizesURLAndWebKitCancellationErrors() {
        #expect(isCancelledMetamapsNavigationError(URLError(.cancelled)))
        #expect(isCancelledMetamapsNavigationError(
            NSError(domain: "WKErrorDomain", code: 102)))
        #expect(!isCancelledMetamapsNavigationError(URLError(.timedOut)))
        #expect(!isCancelledMetamapsNavigationError(
            NSError(domain: "WKErrorDomain", code: 101)))
    }
}

// The web map accepts only ASCII language tags, so a tag it would reject must fail before it is sent.
struct MapViewLanguageTagTests {
    @Test func acceptsOnlyTheTagsTheWebMapAccepts() throws {
        try MetamapsMapViewConfiguration.validateLanguage("zh-Hans")
        #expect(throws: MetamapsError.self) { try MetamapsMapViewConfiguration.validateLanguage("español") }
        #expect(throws: MetamapsError.self) { try MetamapsMapViewConfiguration.validateLanguage("") }
        #expect(throws: MetamapsError.self) {
            try MetamapsMapViewConfiguration(mapSlug: "example", language: "en US").validate()
        }
    }
}

// Guards against breaking the fixed query order, the deterministic order of extra queries, or extra values winning on collision.
struct MapViewURLConstructionTests {
    @Test func additionalQueryOverridesFixedKeysWithoutDuplicates() throws {
        let group = UUID(uuidString: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")!
        let floor = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        let url = try makeMetamapsMapURL(configuration: .init(
            mapSlug: "office map",
            baseURL: URL(string: "https://metamaps.jp")!,
            groupId: group,
            language: "ja",
            initialFloorId: floor,
            previewToken: "fixed",
            additionalQuery: [
                "group": "override",
                "preview": "additional",
                "z": "last",
                "a": "first",
            ],
            bleTest: true))

        #expect(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems == [
            URLQueryItem(name: "group", value: "override"),
            URLQueryItem(name: "lang", value: "ja"),
            URLQueryItem(name: "floor", value: floor.uuidString.lowercased()),
            URLQueryItem(name: "preview", value: "additional"),
            URLQueryItem(name: "bletest", value: "1"),
            URLQueryItem(name: "a", value: "first"),
            URLQueryItem(name: "z", value: "last"),
        ])
    }
}

struct MapViewSpotStableKeyTests {
    @Test func acceptsNewAndLegacyStableKeysWithoutRewriting() {
        #expect(isValidMetamapsSpotStableKey("6Y6SSBY5"))
        #expect(isValidMetamapsSpotStableKey("spot_6Y6SSBY5"))
        #expect(!isValidMetamapsSpotStableKey(""))
        #expect(!isValidMetamapsSpotStableKey("6Y6S/SBY5"))
    }
}

// Guards against changes to the external link scheme boundary and the default decision without a hook.
struct MapViewExternalLinkPolicyTests {
    @Test func allowsOnlyDocumentedSchemesAndDefaultsToOpen() {
        for scheme in ["http", "https", "tel", "mailto", "sms", "geo"] {
            let url = URL(string: "\(scheme):example")!
            #expect(isAllowedMetamapsExternalLink(url))
            #expect(defaultMetamapsExternalLinkDecision(for: url) == .openDefault)
        }
        let blocked = URL(string: "javascript:alert(1)")!
        #expect(!isAllowedMetamapsExternalLink(blocked))
        #expect(defaultMetamapsExternalLinkDecision(for: blocked) == nil)
    }
}

// Guards against losing the SDK identifier and the optional appendix in the User-Agent string.
struct MapViewUserAgentTests {
    @Test func appendsHostIdentifierWhenProvided() {
        #expect(makeMetamapsUserAgent(applicationVersion: "1.2.3", appendix: nil)
                == "Metamaps/1.2.3")
        #expect(makeMetamapsUserAgent(applicationVersion: "1.2.3", appendix: "ExampleApp/4")
                == "Metamaps/1.2.3 ExampleApp/4")
    }
}

// Microphone requests from web content are granted automatically only for the main frame of the configured HTTPS origin.
struct MapViewMicrophonePermissionPolicyTests {
    @Test func grantsConfiguredHTTPSOriginIncludingCustomHostAndPort() {
        #expect(shouldAutomaticallyGrantMetamapsMicrophonePermission(
            baseURL: URL(string: "https://staging.example.test:8443/metamap")!,
            requestingScheme: "https",
            requestingHost: "STAGING.EXAMPLE.TEST",
            requestingPort: 8443,
            isMainFrame: true,
            isMicrophoneOnly: true))

        #expect(shouldAutomaticallyGrantMetamapsMicrophonePermission(
            baseURL: URL(string: "https://metamaps.jp")!,
            requestingScheme: "https",
            requestingHost: "metamaps.jp",
            requestingPort: 0,
            isMainFrame: true,
            isMicrophoneOnly: true))
    }

    @Test func doesNotGrantUntrustedInsecureOrSubframeRequests() {
        let baseURL = URL(string: "https://staging.example.test:8443")!
        let requests: [(String, String, Int, Bool)] = [
            ("http", "staging.example.test", 8443, true),
            ("https", "other.example.test", 8443, true),
            ("https", "staging.example.test", 443, true),
            ("https", "staging.example.test", 8443, false),
        ]
        for (scheme, host, port, isMainFrame) in requests {
            #expect(!shouldAutomaticallyGrantMetamapsMicrophonePermission(
                baseURL: baseURL,
                requestingScheme: scheme,
                requestingHost: host,
                requestingPort: port,
                isMainFrame: isMainFrame,
                isMicrophoneOnly: true))
        }

        #expect(!shouldAutomaticallyGrantMetamapsMicrophonePermission(
            baseURL: URL(string: "http://localhost:5000")!,
            requestingScheme: "http",
            requestingHost: "localhost",
            requestingPort: 5000,
            isMainFrame: true,
            isMicrophoneOnly: true))

        #expect(!shouldAutomaticallyGrantMetamapsMicrophonePermission(
            baseURL: baseURL,
            requestingScheme: "https",
            requestingHost: "staging.example.test",
            requestingPort: 8443,
            isMainFrame: true,
            isMicrophoneOnly: false))
    }
}
