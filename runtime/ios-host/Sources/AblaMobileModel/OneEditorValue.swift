/// Mirrors the portable One input's ASCII number/decimal rules. Text (including
/// secure text) has no case, spelling or Unicode normalization. Truncation never
/// splits a UTF-16 surrogate or a user-perceived character.
public enum OneEditorValue {
    public static func normalized(_ raw: String, keyboard: String, maximumUTF16: Int) -> String {
        let limit = max(0, min(16384, maximumUTF16))
        var filtered = raw
        if keyboard == "number" {
            filtered = String(String.UnicodeScalarView(raw.unicodeScalars.filter { (48...57).contains($0.value) }))
        } else if keyboard == "decimal" {
            var separator = false
            filtered = String(String.UnicodeScalarView(raw.unicodeScalars.filter {
                if $0.value == 44 || $0.value == 46 {
                    if separator { return false }
                    separator = true; return true
                }
                return (48...57).contains($0.value)
            }))
        }
        var result = ""
        var size = 0
        for character in filtered {
            let count = String(character).utf16.count
            if count > limit - size { break }
            result.append(character); size += count
        }
        return result
    }
}
