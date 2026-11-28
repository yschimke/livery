import Foundation
import Livery
import LiveryUI
import RcNativePlayerCore
import RcPlayerAppleFonts
import SwiftUI

#if canImport(UIKit) && !os(watchOS) && !os(tvOS)
  import RcNativePlayerUIKit
  import UIKit

  /// Resolves document image references through Livery's verified blob store, image proxy / host
  /// allowlist, and optional app-provided image loader (PROTOCOL.md §6.1, §6.2).
  public final class LiveryNativeResourceResolver: RemoteComposeNativeResourceResolving {
    private let bindings: Bindings
    private let assetBytes: @Sendable (String) async -> Data?
    private let imageProxy: @Sendable () async -> ImageProxy?
    private let directHosts: Set<String>
    private let appImages: (@Sendable (String) async -> Data?)?
    private let session: URLSession

    public init(
      bindings: Bindings,
      assetBytes: @escaping @Sendable (String) async -> Data?,
      imageProxy: @escaping @Sendable () async -> ImageProxy? = { nil },
      directHosts: Set<String> = [],
      appImages: (@Sendable (String) async -> Data?)? = nil,
      session: URLSession = URLSessionFetcher.defaultSession
    ) {
      self.bindings = bindings
      self.assetBytes = assetBytes
      self.imageProxy = imageProxy
      self.directHosts = directHosts
      self.appImages = appImages
      self.session = session
    }

    public func resolve(_ request: RemoteComposeNativeResourceRequest) async throws -> Data {
      let refString: String
      if request.reference.hasPrefix("livery:value/") {
        let inputName = String(request.reference.dropFirst("livery:value/".count))
        guard case .image(let boundRef)? = bindings.values[inputName] else {
          throw RemoteComposeNativeResourceError.unresolvedReference(id: request.id)
        }
        refString = boundRef
      } else {
        refString = request.reference
      }

      guard let ref = ImageRef.parse(refString) else {
        throw RemoteComposeNativeResourceError.invalidReference(id: request.id)
      }

      switch ref {
      case .asset(let sha256):
        guard let data = await assetBytes(sha256) else {
          throw RemoteComposeNativeResourceError.unresolvedReference(id: request.id)
        }
        return data
      case .app(let key):
        guard let data = await appImages?(key) else {
          throw RemoteComposeNativeResourceError.unresolvedReference(id: request.id)
        }
        return data
      case .remote(let url):
        let proxy = await imageProxy()
        let widthPx = max(request.declaredWidth, 1)
        let heightPx = max(request.declaredHeight, 1)
        guard
          let allowed = ResourceURLs.remote(
            proxy: proxy, directHosts: directHosts, url: url, widthPx: widthPx, heightPx: heightPx),
          let targetURL = URL(string: allowed)
        else {
          throw RemoteComposeNativeResourceError.invalidReference(id: request.id)
        }
        let (data, response) = try await session.data(from: targetURL)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
          throw RemoteComposeNativeResourceError.unresolvedReference(id: request.id)
        }
        return data
      }
    }
  }

  /// Resolves catalog font assets from `LoadedTemplate.fontAsset(_:)` and optionally delegates
  /// `google:` families to a `RemoteComposeDownloadableFontResolving` (e.g. `RemoteComposeGoogleFontsResolver`).
  public final class LiveryNativeFontResolver: RemoteComposeDownloadableFontResolving {
    private let template: LoadedTemplate
    private let assetBytes: @Sendable (String) async -> Data?
    private let fallback: (any RemoteComposeDownloadableFontResolving)?

    public init(
      template: LoadedTemplate,
      assetBytes: @escaping @Sendable (String) async -> Data?,
      fallback: (any RemoteComposeDownloadableFontResolving)? = nil
    ) {
      self.template = template
      self.assetBytes = assetBytes
      self.fallback = fallback
    }

    public func resolve(_ request: RemoteComposeDownloadableFontRequest) async throws
      -> RemoteComposeDownloadedFont
    {
      let face = FontFace(family: request.family, weight: 400, italic: false)
      if let asset = template.fontAsset(face), let data = await assetBytes(asset.sha256) {
        return RemoteComposeDownloadedFont(
          family: request.family, identity: asset.sha256, data: data)
      }
      if let fallback {
        return try await fallback.resolve(request)
      }
      throw RemoteComposeDownloadableFontError.noCompatibleFont(family: request.family)
    }
  }

  /// SwiftUI player adapter bridging `LiveryUI`'s `PlayerInput` to `rc-swift`'s pure-Swift
  /// `RemoteComposeNativePlayerView`, with incremental value pushing and Livery image/font resolvers.
  @MainActor
  public struct LiveryRcNativePlayer: UIViewRepresentable {
    public let input: PlayerInput
    public let background: RemoteComposeNativePlayerBackground
    public let assetBytes: @Sendable (String) async -> Data?
    public let imageProxy: @Sendable () async -> ImageProxy?
    public let directImageHosts: Set<String>
    public let appImages: (@Sendable (String) async -> Data?)?
    public let fontFallback: (any RemoteComposeDownloadableFontResolving)?

    public init(
      input: PlayerInput,
      background: RemoteComposeNativePlayerBackground = .transparent,
      host: LiveryHost? = nil,
      appImages: (@Sendable (String) async -> Data?)? = nil,
      fontFallback: (any RemoteComposeDownloadableFontResolving)? = nil
    ) {
      self.input = input
      self.background = background
      if let host {
        self.assetBytes = { sha in await host.assetBytes(sha256: sha) }
        self.imageProxy = { await host.imageProxy() }
        self.directImageHosts = host.directImageHosts
      } else {
        self.assetBytes = { _ in nil }
        self.imageProxy = { nil }
        self.directImageHosts = []
      }
      self.appImages = appImages
      self.fontFallback = fontFallback
    }

    public init(
      input: PlayerInput,
      background: RemoteComposeNativePlayerBackground = .transparent,
      assetBytes: @escaping @Sendable (String) async -> Data?,
      imageProxy: @escaping @Sendable () async -> ImageProxy? = { nil },
      directImageHosts: Set<String> = [],
      appImages: (@Sendable (String) async -> Data?)? = nil,
      fontFallback: (any RemoteComposeDownloadableFontResolving)? = nil
    ) {
      self.input = input
      self.background = background
      self.assetBytes = assetBytes
      self.imageProxy = imageProxy
      self.directImageHosts = directImageHosts
      self.appImages = appImages
      self.fontFallback = fontFallback
    }

    public func makeCoordinator() -> Coordinator { Coordinator() }

    public func makeUIView(context: Context) -> RemoteComposeNativePlayerView {
      let resourceResolver = LiveryNativeResourceResolver(
        bindings: input.bindings,
        assetBytes: assetBytes,
        imageProxy: imageProxy,
        directHosts: directImageHosts,
        appImages: appImages
      )
      let fontResolver = LiveryNativeFontResolver(
        template: input.template,
        assetBytes: assetBytes,
        fallback: fontFallback
      )
      let view = RemoteComposeNativePlayerView(
        data: input.document,
        background: background,
        compatibilityPolicy: .compatible,
        androidCompatibility: .enabled,
        customComponents: nil,
        resourceResolver: resourceResolver,
        downloadableFontResolver: fontResolver,
        onEvent: { event in handle(event) },
        onDiagnostics: { _ in }
      )
      context.coordinator.loaded = input.document
      context.coordinator.apply(input.values, to: view)
      return view
    }

    public func updateUIView(_ view: RemoteComposeNativePlayerView, context: Context) {
      view.onEvent = { event in handle(event) }
      if context.coordinator.loaded != input.document {
        let resourceResolver = LiveryNativeResourceResolver(
          bindings: input.bindings,
          assetBytes: assetBytes,
          imageProxy: imageProxy,
          directHosts: directImageHosts,
          appImages: appImages
        )
        let fontResolver = LiveryNativeFontResolver(
          template: input.template,
          assetBytes: assetBytes,
          fallback: fontFallback
        )
        view.configureResources(limits: .default, resolver: resourceResolver)
        view.configureDownloadableFonts(resolver: fontResolver)
        view.load(input.document)
        context.coordinator.loaded = input.document
        context.coordinator.pushed = [:]
      }
      context.coordinator.apply(input.values, to: view)
    }

    private func handle(_ event: RemoteComposeNativePlayerEvent) {
      guard case .namedAction(let name, let value) = event else { return }
      switch value {
      case .none: input.onAction(name, nil)
      case .text(let text): input.onAction(name, text)
      case .integer(let i): input.onAction(name, String(i))
      case .float(let f): input.onAction(name, String(f))
      case .floatList: return
      }
    }

    @MainActor
    public final class Coordinator {
      var loaded: Data?
      var pushed: [String: PlayerValue] = [:]

      /// Pushes only what changed, so a live update animates and nothing else restarts.
      func apply(_ values: [String: PlayerValue], to view: RemoteComposeNativePlayerView) {
        let changed = values.filter { pushed[$0.key] != $0.value }
        pushed = values
        Task { @MainActor in
          for (name, v) in changed {
            switch v {
            case .text(let s): _ = await view.setString(s, for: name)
            case .integer(let i): _ = await view.setInteger(i, for: name)
            case .float(let f): _ = await view.setFloat(f, for: name)
            case .color(let c): _ = await view.setColor(c, for: name)
            }
          }
        }
      }
    }
  }
#endif
