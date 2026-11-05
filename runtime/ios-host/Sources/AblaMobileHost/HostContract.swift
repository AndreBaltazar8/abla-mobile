import AblaMobileModel
import CAblaMobile

/// The renderer is compiled for iOS; portable contract tests do not claim an
/// iOS UI run. Actual archive and simulator integration are separate CI gates.
public enum AblaMobileHostContract {
    public static let protocolVersion = 1
    public static let abiVersion = 1
}
