#if os(iOS)
import SwiftUI
import UIKit
import AblaMobileModel

/// Initial protocol-v1 renderer. Unsupported nodes are visible refusals rather
/// than silent substitutions; rich media, games and WebView parity is later.
public struct AblaMobileRoot: View {
    @ObservedObject private var store: MobileRuntimeStore
    public init(store: MobileRuntimeStore) { self.store = store }
    public var body: some View {
        Group {
            if store.failure != nil { Text("Application unavailable").accessibilityIdentifier("runtime.failure") }
            else if let tree = store.tree {
                NavigationStack {
                    ZStack {
                        (OneRGBA(tree.background)?.swiftColor ?? Color(uiColor: .systemBackground)).ignoresSafeArea()
                        OneNodeView(node: tree.root, revision: tree.revision, identity: tree.presentationIdentity, store: store)
                            .id(tree.presentationIdentity)
                    }
                        .navigationTitle(tree.showTopBar ? tree.title : "")
                        .preferredColorScheme(tree.darkTheme ? .dark : .light)
                        .safeAreaInset(edge: .bottom) {
                            if let notice = store.notice { Text(notice).accessibilityIdentifier("runtime.notice") }
                        }
                }
            } else { ProgressView().accessibilityIdentifier("runtime.loading") }
        }
        .onAppear { store.start() }
        .fileImporter(isPresented: Binding(get: { store.pickingFile }, set: { store.setFilePickerPresented($0) }),
                      allowedContentTypes: store.fileTypes, allowsMultipleSelection: store.multipleFiles) {
            store.filesPicked($0)
        }
        // A scene background transition is not process shutdown. The app host
        // explicitly calls stop only when it intends to end this runtime.
    }
}

@MainActor private struct OneNodeView: View {
    let node: OneNode
    let revision: Int64
    let identity: String
    @ObservedObject var store: MobileRuntimeStore
    private var gap: CGFloat { CGFloat(max(0, min(256, node.number("gap", default: 8)))) }
    private var children: some View {
        ForEach(node.children) { child in AnyView(OneNodeView(node: child, revision: revision, identity: identity, store: store)) }
    }
    // Container identifiers propagate to descendants in SwiftUI and would
    // replace every editor/button's own key. Assign identity on controls only.
    @ViewBuilder var body: some View {
        if ["one.pressable", "one.link", "button", "one.input"].contains(node.type) { content }
        else { content.modifier(OneDecoration(node: node)) }
    }
    @ViewBuilder private var content: some View {
        switch node.type {
        case "one.column", "column", "theme", "surface", "card":
            VStack(alignment: .leading, spacing: gap) { children }
        case "one.row", "row": HStack(spacing: gap) { children }
        case "one.stack": ZStack { children }
        case "one.scroll", "one.feed", "list": ScrollView { VStack(alignment: .leading, spacing: gap) { children } }
        case "one.scrollRow": ScrollView(.horizontal) { HStack(spacing: gap) { children } }
        case "one.text", "text":
            Text(node.attribute("text", default: node.value("text")))
                .font(.system(size: CGFloat(max(8, min(96, node.number("size", default: 16))))))
                .accessibilityIdentifier(node.key)
        case "one.pressable", "one.link", "button":
            Button { _ = store.send(node.event("onPress"), revision: revision, identity: identity) } label: {
                if node.children.isEmpty { Text(node.attribute("text", default: node.value("text"))) }
                else { VStack(spacing: gap) { children } }
            }.disabled(node.attribute("enabled") == "false" || node.event("onPress") == 0)
                .buttonStyle(OneControlStyle(node: node))
                .accessibilityIdentifier(node.key)
        case "one.input": OneInputView(node: node, revision: revision, identity: identity, store: store).id(node.key).accessibilityIdentifier(node.key)
        case "one.spacer", "spacer": Spacer(minLength: 0)
        case "one.divider", "divider": Divider()
        case "one.native":
            if let fallback = node.children.last { AnyView(OneNodeView(node: fallback, revision: revision, identity: identity, store: store)) }
            else { Text("Unsupported native content") }
        default: Text("Unsupported component: \(node.type)").accessibilityIdentifier("runtime.unsupported")
        }
    }
}

@MainActor private struct OneInputView: View {
    let node: OneNode
    let revision: Int64
    let identity: String
    @ObservedObject var store: MobileRuntimeStore
    @State private var editing: String
    @State private var echoes: InputEchoes
    @State private var expired: UInt64?
    @FocusState private var focused: Bool
    init(node: OneNode, revision: Int64, identity: String, store: MobileRuntimeStore) {
        self.node = node; self.revision = revision; self.identity = identity; self.store = store
        let initial = node.attribute("value")
        _editing = State(initialValue: initial)
        _echoes = State(initialValue: InputEchoes(initial: initial))
    }
    private var secure: Bool { node.attribute("secure") == "true" }
    private var keyboard: UIKeyboardType {
        switch node.attribute("keyboard") {
        case "number": .numberPad
        case "decimal": .decimalPad
        case "email": .emailAddress
        default: .default
        }
    }
    private var model: String { node.attribute("value") }
    private var binding: Binding<String> {
        Binding(get: { editing }, set: { raw in
            let keyboard = node.attribute("keyboard")
            let fallback = keyboard == "number" ? 8 : keyboard == "decimal" ? 32 : 4000
            let limit = Int(node.attribute("maxLength")) ?? fallback
            let next = OneEditorValue.normalized(raw, keyboard: keyboard, maximumUTF16: limit)
            guard next != editing else { return }
            let event = node.event("onChange")
            guard event > 0 else { editing = next; return }
            var proposed = echoes
            guard proposed.changed(next), store.send(event, revision: revision, identity: identity, payload: next, change: true) else { return }
            echoes = proposed; editing = next
        })
    }
    var body: some View {
        Group {
            if secure && node.attribute("multiline") == "true" {
                Text("Secure multiline input is unsupported").accessibilityIdentifier("runtime.secure_multiline_refused")
            } else if secure {
                SecureField(node.attribute("placeholder"), text: binding)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled(true)
                    .privacySensitive()
            } else if node.attribute("multiline") == "true" {
                TextField(node.attribute("placeholder"), text: binding, axis: .vertical)
            } else {
                TextField(node.attribute("placeholder"), text: binding)
                    .keyboardType(node.attribute("keyboard") == "email" ? .emailAddress : .default)
            }
        }
        .focused($focused)
        .keyboardType(keyboard)
        .modifier(OneDecoration(node: node, focused: focused))
        .disabled(node.attribute("enabled") == "false")
        .onSubmit { _ = store.send(node.event("onSubmit"), revision: revision, identity: identity); focused = false }
        .onChange(of: model) { _, next in accept(next) }
        .onChange(of: focused) { _, _ in accept(model) }
        .task(id: echoes.generation) {
            let generation = echoes.generation
            guard echoes.hasPending else { return }
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            expired = generation; accept(model)
        }
        .onDisappear { editing = ""; echoes.clear() }
    }
    private func accept(_ value: String) {
        if let replacement = echoes.acceptModel(value, focused: focused, expiredGeneration: expired) {
            editing = replacement
        }
    }
}
#endif
