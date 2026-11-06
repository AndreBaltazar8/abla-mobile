#if os(iOS)
import SwiftUI
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
                    OneNodeView(node: tree.root, store: store)
                        .navigationTitle(tree.showTopBar ? tree.title : "")
                        .preferredColorScheme(tree.darkTheme ? .dark : .light)
                }
            } else { ProgressView().accessibilityIdentifier("runtime.loading") }
        }
        .onAppear { store.start() }
        // A scene background transition is not process shutdown. The app host
        // explicitly calls stop only when it intends to end this runtime.
    }
}

@MainActor private struct OneNodeView: View {
    let node: OneNode
    @ObservedObject var store: MobileRuntimeStore
    private var gap: CGFloat { CGFloat(max(0, min(256, node.number("gap", default: 8)))) }
    private var children: some View {
        ForEach(node.children) { child in AnyView(OneNodeView(node: child, store: store)) }
    }
    var body: some View { content.accessibilityIdentifier(node.key) }
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
                .font(.system(size: CGFloat(max(8, min(96, node.number("fontSize", default: 16))))))
        case "one.pressable", "one.link", "button":
            Button { _ = store.send(node.event("onPress")) } label: {
                if node.children.isEmpty { Text(node.attribute("text", default: node.value("text"))) }
                else { VStack(spacing: gap) { children } }
            }.disabled(node.attribute("enabled") == "false" || node.event("onPress") == 0)
        case "one.input": OneInputView(node: node, store: store).id(node.key)
        case "one.spacer", "spacer": Spacer(minLength: 0)
        case "one.divider", "divider": Divider()
        case "one.native":
            if let fallback = node.children.last { AnyView(OneNodeView(node: fallback, store: store)) }
            else { Text("Unsupported native content") }
        default: Text("Unsupported component: \(node.type)").accessibilityIdentifier("runtime.unsupported")
        }
    }
}

@MainActor private struct OneInputView: View {
    let node: OneNode
    @ObservedObject var store: MobileRuntimeStore
    @State private var editing: String
    @State private var echoes: InputEchoes
    @State private var expired: UInt64?
    @FocusState private var focused: Bool
    init(node: OneNode, store: MobileRuntimeStore) {
        self.node = node; self.store = store
        let initial = node.attribute("value")
        _editing = State(initialValue: initial)
        _echoes = State(initialValue: InputEchoes(initial: initial))
    }
    private var secure: Bool { node.attribute("secure") == "true" }
    private var model: String { node.attribute("value") }
    private var binding: Binding<String> {
        Binding(get: { editing }, set: { next in
            let limit = max(0, min(16384, Int(node.attribute("maxLength")) ?? 4000))
            guard next.utf16.count <= limit, next != editing else { return }
            var proposed = echoes
            guard proposed.changed(next), store.send(node.event("onChange"), payload: next, change: true) else { return }
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
        .disabled(node.attribute("enabled") == "false")
        .onSubmit { _ = store.send(node.event("onSubmit")); focused = false }
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
