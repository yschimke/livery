import Foundation

/// Flat named values for a player.
public struct Bindings: Sendable, Equatable {
  public var values: [String: NamedValue]
  public init(_ values: [String: NamedValue] = [:]) { self.values = values }
  public static func + (a: Bindings, b: Bindings) -> Bindings { Bindings(a.values.merging(b.values) { _, new in new }) }
}

public struct BindingProblem: Sendable, Equatable {
  public var name: String
  public var message: String
}

public enum BindResult: Sendable, Equatable {
  case ok(Bindings)
  case invalid([BindingProblem])
}

/// docs/PROTOCOL.md §4.1, the same gate as the Kotlin `ContractBinder`: refuses, never drops.
public enum ContractBinder {
  public static func bind(_ contract: Contract, _ data: [String: JSONValue]) -> BindResult {
    var problems: [BindingProblem] = []
    var out: [String: NamedValue] = [:]
    for key in data.keys.sorted() where contract.inputs[key] == nil && contract.lists[key] == nil {
      problems.append(BindingProblem(name: key, message: "not in \(contract.ref)"))
    }
    for (name, spec) in contract.inputs.sorted(by: { $0.key < $1.key }) {
      let given = data[name].flatMap { $0 == .null ? nil : $0 }
      guard let raw = given ?? spec.default else {
        if spec.required { problems.append(BindingProblem(name: name, message: "required")) }
        continue
      }
      if let v = coerce(name, spec, raw, &problems) { out[name] = v }
    }
    for (name, list) in contract.lists.sorted(by: { $0.key < $1.key }) {
      guard let raw = data[name] else { continue }
      guard case .array(let items) = raw else { problems.append(BindingProblem(name: name, message: "expected a list")); continue }
      if items.count > list.max { problems.append(BindingProblem(name: name, message: "\(items.count) items, at most \(list.max)")) }
      out["\(name).count"] = .int(Int32(min(items.count, list.max)))
      for (i, item) in items.prefix(list.max).enumerated() {
        guard case .object(let obj) = item else { problems.append(BindingProblem(name: "\(name).\(i)", message: "expected an object")); continue }
        for key in obj.keys.sorted() where list.item[key] == nil {
          problems.append(BindingProblem(name: "\(name).\(i).\(key)", message: "not in list item"))
        }
        for (field, spec) in list.item.sorted(by: { $0.key < $1.key }) {
          let key = "\(name).\(i).\(field)"
          guard let v = obj[field].flatMap({ $0 == .null ? nil : $0 }) ?? spec.default else {
            if spec.required { problems.append(BindingProblem(name: key, message: "required")) }
            continue
          }
          if let nv = coerce(key, spec, v, &problems) { out[key] = nv }
        }
      }
    }
    return problems.isEmpty ? .ok(Bindings(out)) : .invalid(problems)
  }

  private static func coerce(_ name: String, _ spec: InputSpec, _ raw: JSONValue, _ problems: inout [BindingProblem]) -> NamedValue? {
    func bad() -> NamedValue? { problems.append(BindingProblem(name: name, message: "expected a \(spec.type.rawValue)")); return nil }
    func ranged(_ d: Double) -> Bool {
      if let lo = spec.min, d < lo { problems.append(BindingProblem(name: name, message: "\(d) < \(lo)")); return false }
      if let hi = spec.max, d > hi { problems.append(BindingProblem(name: name, message: "\(d) > \(hi)")); return false }
      return true
    }
    switch spec.type {
    case .string:
      guard case .string(let s) = raw else { return bad() }
      if let maxLength = spec.maxLength, s.utf16.count > maxLength {
        problems.append(BindingProblem(name: name, message: "\(s.utf16.count) chars, at most \(maxLength)")); return nil
      }
      return .string(s)
    case .int:
      guard let i = integral(raw), i >= Int64(Int32.min), i <= Int64(Int32.max) else { return bad() }
      return ranged(Double(i)) ? .int(Int32(i)) : nil
    case .long:
      guard let i = integral(raw) else { return bad() }
      return ranged(Double(i)) ? .long(i) : nil
    case .float:
      guard let d = raw.number else { return bad() }
      return ranged(d) ? .float(Float(d)) : nil
    case .bool:
      guard case .bool(let b) = raw else { return bad() }
      return .bool(b)
    case .color:
      guard case .string(let s) = raw, let argb = parseColor(s) else { return bad() }
      return .color(argb: argb)
    case .image:
      guard case .string(let s) = raw, ImageRef.parse(s) != nil else {
        problems.append(BindingProblem(name: name, message: "expected an image: asset:<sha256>, https://… or app:<key>")); return nil
      }
      return .image(ref: s)
    }
  }

  /// A JSON number with a whole-number value, `3`, `3.0` and `3e0` alike (PROTOCOL.md §4.1),
  /// whichever way the decoder classified the token; beyond 2^53 only an exact integer.
  static func integral(_ raw: JSONValue) -> Int64? {
    switch raw {
    case .int(let i): return i
    case .double(let d): return d == d.rounded(.towardZero) && abs(d) <= 9_007_199_254_740_992 ? Int64(d) : nil
    default: return nil
    }
  }

  /// `#RRGGBB` (opaque) or `#AARRGGBB` → ARGB.
  public static func parseColor(_ s: String) -> Int64? {
    guard s.hasPrefix("#") else { return nil }
    let hex = s.dropFirst()
    guard hex.allSatisfy({ $0.isHexDigit && $0.isASCII }), let v = Int64(hex, radix: 16) else { return nil }
    switch hex.count {
    case 6: return 0xFF00_0000 | v
    case 8: return v
    default: return nil
    }
  }
}
