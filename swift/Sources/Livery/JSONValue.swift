import Foundation

/// Any JSON value, keeping strings, integers, other numbers and booleans apart, as contract
/// binding needs (a JSON string is never a number here).
public enum JSONValue: Equatable, Sendable, Codable {
  case null
  case bool(Bool)
  case int(Int64)
  case double(Double)
  case string(String)
  case array([JSONValue])
  case object([String: JSONValue])

  public init(from decoder: Decoder) throws {
    let c = try decoder.singleValueContainer()
    if c.decodeNil() { self = .null; return }
    if let b = try? c.decode(Bool.self) { self = .bool(b); return }
    if let i = try? c.decode(Int64.self) { self = .int(i); return }
    if let d = try? c.decode(Double.self) { self = .double(d); return }
    if let s = try? c.decode(String.self) { self = .string(s); return }
    if let a = try? c.decode([JSONValue].self) { self = .array(a); return }
    self = .object(try c.decode([String: JSONValue].self))
  }

  public func encode(to encoder: Encoder) throws {
    var c = encoder.singleValueContainer()
    switch self {
    case .null: try c.encodeNil()
    case .bool(let b): try c.encode(b)
    case .int(let i): try c.encode(i)
    case .double(let d): try c.encode(d)
    case .string(let s): try c.encode(s)
    case .array(let a): try c.encode(a)
    case .object(let o): try c.encode(o)
    }
  }

  var number: Double? {
    switch self {
    case .int(let i): return Double(i)
    case .double(let d): return d
    default: return nil
    }
  }
}
