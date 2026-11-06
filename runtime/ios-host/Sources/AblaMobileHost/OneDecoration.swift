#if os(iOS)
import SwiftUI
import AblaMobileModel

extension OneRGBA {
    var swiftColor: Color { Color(.sRGB, red: red, green: green, blue: blue, opacity: alpha) }
}
struct OneDecoration: ViewModifier {
    let node: OneNode
    var pressed = false
    var focused = false
    func body(content: Content) -> some View {
        let style = OneResolvedStyle(node)
        let radius = CGFloat(style.bounded("radius", fallback: 0))
        let shape = RoundedRectangle(cornerRadius: radius)
        let background = style.color("background", pressed: pressed, focused: focused)?.swiftColor ?? .clear
        let border = style.color("borderColor", pressed: pressed, focused: focused)?.swiftColor ?? .clear
        let width = style.dimension("width").map(CGFloat.init)
        let height = style.dimension("height").map(CGFloat.init)
        let minWidth = style.dimension("minWidth").map(CGFloat.init)
        let minHeight = style.dimension("minHeight").map(CGFloat.init)
        let maxWidth = style.dimension("maxWidth").map { max(CGFloat($0), minWidth ?? 0) }
        let maxHeight = style.dimension("maxHeight").map { max(CGFloat($0), minHeight ?? 0) }
        content
            .padding(EdgeInsets(top: style.padding("Top"), leading: style.padding("Left"),
                                bottom: style.padding("Bottom"), trailing: style.padding("Right")))
            .frame(width: width, height: height)
            .frame(minWidth: minWidth,
                   maxWidth: node.value("width") == "fill" ? .infinity : maxWidth,
                   minHeight: minHeight,
                   maxHeight: node.value("height") == "fill" ? .infinity : maxHeight)
            .background(background, in: shape)
            .overlay { shape.stroke(border, lineWidth: CGFloat(style.bounded("border", fallback: 0))) }
            .opacity(style.bounded("opacity", fallback: 100, maximum: 100) / 100)
            .foregroundStyle(style.color("color", pressed: pressed, focused: focused)?.swiftColor ?? .primary)
    }
}
struct OneControlStyle: ButtonStyle {
    let node: OneNode
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.modifier(OneDecoration(node: node, pressed: configuration.isPressed))
    }
}
#endif
