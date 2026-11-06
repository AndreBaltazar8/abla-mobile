import Foundation

public enum HostFileError: Error, Equatable { case invalidFile, tooLarge, full, unknownHandle, invalidRange }
public struct HostFileMetadata: Codable, Equatable, Sendable {
    public let handle: String
    public let name: String
    public let size: Int
    public let mime: String
}

/// Process-scoped opaque handles. Only the native picker supplies source URLs;
/// application requests never receive a filesystem path or nominate one to read.
@MainActor public final class HostFiles {
    private let directory: URL
    private let maximumFileBytes: Int
    private let maximumTotalBytes: Int
    private let maximumFiles: Int
    private var entries: [String: (URL, HostFileMetadata)] = [:]
    private var retainedBytes = 0
    public init(parent: URL = FileManager.default.temporaryDirectory,
                maximumFileBytes: Int = 16 * 1024 * 1024,
                maximumTotalBytes: Int = 32 * 1024 * 1024, maximumFiles: Int = 32) throws {
        self.maximumFileBytes = max(0, min(16 * 1024 * 1024, maximumFileBytes))
        self.maximumTotalBytes = max(0, min(32 * 1024 * 1024, maximumTotalBytes))
        self.maximumFiles = max(0, min(32, maximumFiles))
        directory = parent.appendingPathComponent("abla-files-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false,
                                               attributes: [.posixPermissions: 0o700])
        var excluded = URLResourceValues(); excluded.isExcludedFromBackup = true
        var folder = directory; try? folder.setResourceValues(excluded)
    }
    public func importPicked(_ source: URL, mime: String = "application/octet-stream") throws -> HostFileMetadata {
        guard entries.count < maximumFiles else { throw HostFileError.full }
        let facts = try source.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard facts.isRegularFile == true, facts.isSymbolicLink != true else { throw HostFileError.invalidFile }
        let file = try FileHandle(forReadingFrom: source)
        defer { try? file.close() }
        var bytes = Data()
        while bytes.count <= maximumFileBytes {
            let remaining = maximumFileBytes + 1 - bytes.count
            let chunk = try file.read(upToCount: min(64 * 1024, remaining)) ?? Data()
            if chunk.isEmpty { break }
            bytes.append(chunk)
        }
        guard bytes.count <= maximumFileBytes else { throw HostFileError.tooLarge }
        guard bytes.count <= maximumTotalBytes - retainedBytes else { throw HostFileError.full }
        let token = UUID().uuidString
        let handle = "abla-file:" + token
        let destination = directory.appendingPathComponent(token)
        try bytes.write(to: destination, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: destination.path)
        let name = String(source.lastPathComponent.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }.map(String.init).joined().prefix(256))
        let metadata = HostFileMetadata(handle: handle, name: name, size: bytes.count, mime: String(mime.prefix(128)))
        entries[handle] = (destination, metadata); retainedBytes += bytes.count
        return metadata
    }
    public func read(_ handle: String, offset: Int64, length: Int) throws -> Data {
        guard offset >= 0, length >= 0, length <= 256 * 1024 else { throw HostFileError.invalidRange }
        guard let entry = entries[handle] else { throw HostFileError.unknownHandle }
        let file = try FileHandle(forReadingFrom: entry.0)
        defer { try? file.close() }
        try file.seek(toOffset: UInt64(offset))
        return try file.read(upToCount: length) ?? Data()
    }
    @discardableResult public func delete(_ handle: String) throws -> Bool {
        guard let entry = entries[handle] else { return false }
        try FileManager.default.removeItem(at: entry.0)
        entries.removeValue(forKey: handle); retainedBytes -= entry.1.size
        return true
    }
    public func clear() {
        for entry in entries.values { try? FileManager.default.removeItem(at: entry.0) }
        entries.removeAll(); retainedBytes = 0
    }
    deinit { try? FileManager.default.removeItem(at: directory) }
}
