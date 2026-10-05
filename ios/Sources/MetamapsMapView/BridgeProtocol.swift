import Foundation
import MetamapsPositioning

enum BridgeJSONValue: Codable, Equatable, Sendable {
    case object([String: BridgeJSONValue])
    case array([BridgeJSONValue])
    case string(String)
    case number(Double)
    case bool(Bool)
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null }
        else if let value = try? container.decode(Bool.self) { self = .bool(value) }
        else if let value = try? container.decode(Double.self) { self = .number(value) }
        else if let value = try? container.decode(String.self) { self = .string(value) }
        else if let value = try? container.decode([String: BridgeJSONValue].self) { self = .object(value) }
        else if let value = try? container.decode([BridgeJSONValue].self) { self = .array(value) }
        else { throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unsupported bridge JSON value") }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .object(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }

    static func encode<T: Encodable>(_ value: T) throws -> Self {
        try JSONDecoder().decode(Self.self, from: JSONEncoder.metamapsBridge.encode(value))
    }
}

struct BridgeEnvelope: Codable, Equatable, Sendable {
    static let schema = "metamap.native-bridge.v1"

    let schema: String
    let bridgeVersion: String
    let type: String
    let mapId: UUID?
    let groupId: UUID?
    let sequence: Int64
    let payload: BridgeJSONValue?

    init(
        type: String,
        mapId: UUID? = nil,
        groupId: UUID? = nil,
        sequence: Int64,
        payload: BridgeJSONValue? = nil,
        // The generation that includes `bridge.hello.payload.automaticPositioning` for automatic positioning.
        bridgeVersion: String = "1.4"
    ) {
        schema = Self.schema
        self.bridgeVersion = bridgeVersion
        self.type = type
        self.mapId = mapId
        self.groupId = groupId
        self.sequence = sequence
        self.payload = payload
    }

    /// The `code` of a `map.error` event, such as `spot_not_found`, or `unknown` when the payload has no usable code.
    var mapErrorCode: String {
        guard case .object(let object) = payload, case .string(let code) = object["code"],
              code.range(of: #"^[a-z0-9_]{1,64}\z"#, options: .regularExpression) != nil else { return "unknown" }
        return code
    }

    /// The host error for a `map.error` event. The web map stays usable, so the error is recoverable and asks the
    /// user for nothing.
    var mapOperationError: MetamapsError {
        .init(code: .mapOperationFailed, message: "The web map could not complete the requested operation.",
              recoverable: true, userAction: .none, debugDetail: "map.error: \(mapErrorCode)")
    }

    var hasSupportedVersion: Bool {
        let parts = bridgeVersion.split(separator: ".", omittingEmptySubsequences: false)
        return parts.count == 2 && parts[0] == "1" && !parts[1].isEmpty
            && parts[1].allSatisfy(\.isNumber)
    }
}

struct BridgeReadyContext: Equatable, Sendable {
    let mapId: UUID
    let groupId: UUID
    let configRevision: String?
    let manifestRevision: Int?
}

struct BridgeValidator {
    private(set) var ready: BridgeReadyContext?
    private(set) var handshakeComplete = false
    private(set) var lastInboundSequence: Int64 = -1
    let requestedGroupId: UUID?

    mutating func validate(_ envelope: BridgeEnvelope) throws -> BridgeReadyContext? {
        guard envelope.schema == BridgeEnvelope.schema else { throw bridgeError(.bridgeUnsupported, "Unsupported bridge schema.") }
        guard envelope.hasSupportedVersion else { throw bridgeError(.bridgeUnsupported, "Unsupported bridge version.") }
        guard envelope.sequence > lastInboundSequence else { throw bridgeError(.bridgeHandshakeFailed, "Stale bridge sequence.") }

        if envelope.type == "map.ready" {
            guard let mapId = envelope.mapId, let groupId = envelope.groupId else {
                throw bridgeError(.bridgeHandshakeFailed, "map.ready requires mapId and groupId.")
            }
            if let ready, ready.mapId != mapId || ready.groupId != groupId {
                throw bridgeError(.bridgeMapMismatch, "map.ready attempted to change the pinned map or group.")
            }
            if let requestedGroupId, requestedGroupId != groupId {
                throw bridgeError(.bridgeMapMismatch, "map.ready returned another group.")
            }
            let configRevision: String?
            if case .object(let payload) = envelope.payload, case .string(let value) = payload["configRevision"] {
                configRevision = value
            } else { configRevision = nil }
            let manifestRevision: Int?
            if case .object(let payload) = envelope.payload, case .number(let value) = payload["manifestRevision"],
               value.rounded() == value, value >= 0, value <= Double(Int.max) {
                manifestRevision = Int(value)
            } else { manifestRevision = nil }
            let context = BridgeReadyContext(mapId: mapId, groupId: groupId,
                                             configRevision: configRevision, manifestRevision: manifestRevision)
            if ready != context { handshakeComplete = false }
            ready = context
            lastInboundSequence = envelope.sequence
            return context
        }

        guard let ready else { throw bridgeError(.bridgeHandshakeFailed, "Bridge command arrived before map.ready.") }
        guard envelope.mapId == ready.mapId, envelope.groupId == ready.groupId else {
            throw bridgeError(.bridgeMapMismatch, "Bridge message map/group mismatch.")
        }
        lastInboundSequence = envelope.sequence
        return nil
    }

    mutating func completeHandshake(with context: BridgeReadyContext) -> Bool {
        guard ready == context else { return false }
        handshakeComplete = true
        return true
    }

    mutating func reset() {
        ready = nil
        handshakeComplete = false
        lastInboundSequence = -1
    }

    private func bridgeError(_ code: MetamapsError.Code, _ detail: String) -> MetamapsError {
        .init(code: code, message: "The native map bridge rejected a message.",
              recoverable: code != .bridgeMapMismatch, userAction: .retry, debugDetail: detail)
    }
}

extension JSONEncoder {
    static let metamapsBridge: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return encoder
    }()
}
