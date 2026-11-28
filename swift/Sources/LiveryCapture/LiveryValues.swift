import Foundation
import Livery
import RcCreation

/// Errors thrown while capturing a Swift `RcCreation` template against a Livery `Contract`.
public enum LiveryCaptureError: Error, Equatable, LocalizedError, Sendable {
  case undeclaredInput(name: String, contract: String)
  case wrongInputType(name: String, expected: ValueType, actual: ValueType)
  case undeclaredAction(name: String, contract: String)
  case invalidTemplateName(String)
  case invalidIdentifier(String)

  public var errorDescription: String? {
    switch self {
    case .undeclaredInput(let name, let contract):
      return "Input '\(name)' is not declared by contract \(contract)"
    case .wrongInputType(let name, let expected, let actual):
      return "Input '\(name)' has type \(actual.rawValue), expected \(expected.rawValue)"
    case .undeclaredAction(let name, let contract):
      return "Action '\(name)' is not declared by contract \(contract)"
    case .invalidTemplateName(let name):
      return "Invalid or reserved template name '\(name)'"
    case .invalidIdentifier(let id):
      return "Invalid identifier '\(id)'"
    }
  }
}

/// Checked contract accessors for authoring a Livery template with `RcCreation`.
///
/// Every variable or action requested through `LiveryValues` is verified against the `Contract`
/// (refusing undeclared names and mismatched types) and recorded so the captured template's
/// sidecar (`reads` and `emits`) stays in lockstep with what the template uses.
public final class LiveryValues {
  public let contract: Contract
  private var recordedReads: Set<String> = []
  private var recordedEmits: Set<String> = []

  public init(contract: Contract) {
    self.contract = contract
  }

  public var reads: [String] { recordedReads.sorted() }
  public var emits: [String] { recordedEmits.sorted() }

  public func string(_ name: String, default fallback: String? = nil) throws -> RemoteVariable<String> {
    let spec = try requireInput(name, expected: .string)
    recordedReads.insert(name)
    let value: String
    if let fallback {
      value = fallback
    } else if case .string(let s)? = spec.default {
      value = s
    } else {
      value = ""
    }
    return RemoteVariable(name, default: value)
  }

  public func int(_ name: String, default fallback: Int? = nil) throws -> RemoteVariable<Int> {
    let spec = try requireInput(name, expected: .int)
    recordedReads.insert(name)
    let value: Int
    if let fallback {
      value = fallback
    } else if case .number(let n)? = spec.default {
      value = Int(n)
    } else {
      value = 0
    }
    return RemoteVariable(name, default: value)
  }

  public func float(_ name: String, default fallback: Double? = nil) throws -> RemoteVariable<Double> {
    let spec = try requireInput(name, expected: .float)
    recordedReads.insert(name)
    let value: Double
    if let fallback {
      value = fallback
    } else if case .number(let n)? = spec.default {
      value = n
    } else {
      value = 0
    }
    return RemoteVariable(name, default: value)
  }

  public func bool(_ name: String, default fallback: Bool? = nil) throws -> RemoteVariable<Bool> {
    let spec = try requireInput(name, expected: .bool)
    recordedReads.insert(name)
    let value: Bool
    if let fallback {
      value = fallback
    } else if case .bool(let b)? = spec.default {
      value = b
    } else {
      value = false
    }
    return RemoteVariable(name, default: value)
  }

  public func color(
    _ name: String,
    default fallback: RemoteColorValue? = nil
  ) throws -> RemoteVariable<RemoteColorValue> {
    let spec = try requireInput(name, expected: .color)
    recordedReads.insert(name)
    let value: RemoteColorValue
    if let fallback {
      value = fallback
    } else if case .number(let n)? = spec.default {
      value = Self.colorFromARGB(UInt32(truncatingIfNeeded: Int64(n)))
    } else if case .string(let s)? = spec.default, let parsed = Self.parseHexColor(s) {
      value = parsed
    } else {
      value = .black
    }
    return RemoteVariable(name, default: value)
  }

  /// Validates that `name` is declared in `contract.actions` and records it in `emits`.
  public func action(_ name: String) throws -> String {
    guard contract.actions[name] != nil else {
      throw LiveryCaptureError.undeclaredAction(name: name, contract: contract.ref)
    }
    recordedEmits.insert(name)
    return name
  }

  private func requireInput(_ name: String, expected: ValueType) throws -> InputSpec {
    guard let spec = contract.inputs[name] else {
      throw LiveryCaptureError.undeclaredInput(name: name, contract: contract.ref)
    }
    guard spec.type == expected else {
      throw LiveryCaptureError.wrongInputType(name: name, expected: expected, actual: spec.type)
    }
    return spec
  }

  private static func colorFromARGB(_ argb: UInt32) -> RemoteColorValue {
    let a = Double((argb >> 24) & 0xFF) / 255.0
    let r = Double((argb >> 16) & 0xFF) / 255.0
    let g = Double((argb >> 8) & 0xFF) / 255.0
    let b = Double(argb & 0xFF) / 255.0
    return RemoteColorValue(red: r, green: g, blue: b, opacity: a)
  }

  private static func parseHexColor(_ text: String) -> RemoteColorValue? {
    let clean = text.hasPrefix("#") ? String(text.dropFirst()) : text
    guard let raw = UInt32(clean, radix: 16) else { return nil }
    if clean.count == 6 {
      return .hex(raw)
    }
    if clean.count == 8 {
      return colorFromARGB(raw)
    }
    return nil
  }
}
