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
      let coordinator = context.coordinator
      let view = RemoteComposeNativePlayerView(
        data: input.document,
        background: background,
        compatibilityPolicy: .compatible,
        androidCompatibility: .enabled,
        customComponents: nil,
        resourceResolver: resourceResolver,
        downloadableFontResolver: fontResolver,
        onEvent: { event in handle(event) },
        onDiagnostics: { _ in
          Task { @MainActor in coordinator.retryPending() }
        }
      )
      coordinator.attach(view: view, document: input.document, imageBindings: Self.imageBindings(from: input.bindings))
      coordinator.apply(input.values, to: view)
      return view
    }

    public func updateUIView(_ view: RemoteComposeNativePlayerView, context: Context) {
      let coordinator = context.coordinator
      view.onEvent = { event in handle(event) }
      view.onDiagnostics = { _ in
        Task { @MainActor in coordinator.retryPending() }
      }
      let currentImages = Self.imageBindings(from: input.bindings)
      let keysRemoved = coordinator.pushed.keys.contains { input.values[$0] == nil }
      if coordinator.loaded != input.document
        || coordinator.imageBindings != currentImages
        || keysRemoved
      {
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
        coordinator.attach(view: view, document: input.document, imageBindings: currentImages)
      }
      coordinator.apply(input.values, to: view)
    }

    @available(iOS 16.0, *)
    public func sizeThatFits(
      _ proposal: ProposedViewSize,
      uiView: RemoteComposeNativePlayerView,
      context: Context
    ) -> CGSize? {
      guard let docSize = context.coordinator.documentSize(for: input.document),
        docSize.width > 0, docSize.height > 0
      else { return nil }
      let aspect = docSize.height / docSize.width
      if let width = proposal.width, width.isFinite, width > 0 {
        let height = proposal.height.flatMap { ($0.isFinite && $0 > 0) ? $0 : nil } ?? (width * aspect)
        return CGSize(width: width, height: height)
      }
      if let height = proposal.height, height.isFinite, height > 0 {
        return CGSize(width: height / aspect, height: height)
      }
      return nil
    }

    private static func imageBindings(from bindings: Bindings) -> [String: String] {
      var out: [String: String] = [:]
      for (k, v) in bindings.values {
        if case .image(let ref) = v { out[k] = ref }
      }
      return out
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
      weak var view: RemoteComposeNativePlayerView?
      var loaded: Data?
      var imageBindings: [String: String] = [:]
      var pushed: [String: PlayerValue] = [:]
      private var desired: [String: PlayerValue] = [:]
      private var applyTask: Task<Void, Never>?
      private var cachedSizeDoc: Data?
      private var cachedSize: CGSize?

      func attach(view: RemoteComposeNativePlayerView, document: Data, imageBindings: [String: String]) {
        self.view = view
        self.loaded = document
        self.imageBindings = imageBindings
        self.pushed = [:]
        self.applyTask?.cancel()
        self.applyTask = nil
      }

      func documentSize(for document: Data) -> CGSize? {
        if cachedSizeDoc == document { return cachedSize }
        cachedSizeDoc = document
        if let prepared = try? NativeSwiftPreparedDocument.decode(data: document),
          prepared.width > 0, prepared.height > 0
        {
          cachedSize = CGSize(width: CGFloat(prepared.width), height: CGFloat(prepared.height))
        } else {
          cachedSize = nil
        }
        return cachedSize
      }

      func retryPending() {
        guard let view, !desired.isEmpty else { return }
        apply(desired, to: view)
      }

      /// Pushes only what changed, retrying writes that `.deferred` while the player's async
      /// document open is still in flight so initial text and theme values are never dropped.
      func apply(_ values: [String: PlayerValue], to view: RemoteComposeNativePlayerView) {
        self.view = view
        self.desired = values
        let pending = values.filter { pushed[$0.key] != $0.value }
        guard !pending.isEmpty else { return }

        applyTask?.cancel()
        let generationDoc = loaded
        applyTask = Task { @MainActor [weak self, weak view] in
          guard let self, let view else { return }
          for attempt in 0..<100 {
            guard !Task.isCancelled, self.loaded == generationDoc else { return }
            let remaining = self.desired.filter { self.pushed[$0.key] != $0.value }
            guard !remaining.isEmpty else { return }

            var anyDeferred = false
            for (name, v) in remaining {
              guard !Task.isCancelled, self.loaded == generationDoc else { return }
              let accepted: Bool
              switch v {
              case .text(let s): accepted = await view.setString(s, for: name)
              case .integer(let i): accepted = await view.setInteger(i, for: name)
              case .float(let f): accepted = await view.setFloat(f, for: name)
              case .color(let c): accepted = await view.setColor(c, for: name)
              }
              if accepted {
                self.pushed[name] = v
              } else {
                anyDeferred = true
              }
            }
            if !anyDeferred { return }
            let delayNs: UInt64 = attempt < 10 ? 15_000_000 : 35_000_000
            try? await Task.sleep(nanoseconds: delayNs)
          }
        }
      }
    }
  }
#endif
