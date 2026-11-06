import Foundation
import XCTest
@testable import AblaMobileModel

final class HostFilesTests: XCTestCase {
    @MainActor func testImportCopiesAcrossReadChunksWithoutTruncation() async throws {
        let source = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: source) }
        let bytes = Data((0..<(128 * 1024 + 7)).map { UInt8($0 % 251) })
        try bytes.write(to: source)
        let vault = try HostFiles()
        let imported = try vault.importPicked(source)
        XCTAssertEqual(imported.size, bytes.count)
        XCTAssertEqual(try vault.read(imported.handle, offset: 0, length: 256 * 1024), bytes)
    }
    @MainActor func testHandlesNeverAuthorizePathsAndAreSessionScoped() async throws {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: parent) }
        let source = parent.appendingPathComponent("ação.csv")
        try Data("abcdef".utf8).write(to: source)
        let vault = try HostFiles(parent: parent)
        let metadata = try vault.importPicked(source)
        XCTAssertTrue(metadata.handle.hasPrefix("abla-file:"))
        XCTAssertFalse(metadata.handle.contains(source.path))
        XCTAssertEqual(metadata.name, "ação.csv")
        XCTAssertEqual(try vault.read(metadata.handle, offset: 2, length: 3), Data("cde".utf8))
        XCTAssertThrowsError(try vault.read(source.path, offset: 0, length: 1))
        let other = try HostFiles(parent: parent)
        XCTAssertThrowsError(try other.read(metadata.handle, offset: 0, length: 1))
        XCTAssertThrowsError(try vault.read(metadata.handle, offset: -1, length: 1))
        XCTAssertThrowsError(try vault.read(metadata.handle, offset: 0, length: 256 * 1024 + 1))
        vault.clear()
        XCTAssertThrowsError(try vault.read(metadata.handle, offset: 0, length: 1))
    }
    @MainActor func testQuotasAndSymlinkRefusalPreserveExistingFile() async throws {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: parent) }
        let source = parent.appendingPathComponent("source")
        try Data("1234".utf8).write(to: source)
        let vault = try HostFiles(parent: parent, maximumFileBytes: 4, maximumTotalBytes: 4, maximumFiles: 2)
        let first = try vault.importPicked(source)
        XCTAssertThrowsError(try vault.importPicked(source))
        XCTAssertEqual(try vault.read(first.handle, offset: 0, length: 4), Data("1234".utf8))
        XCTAssertTrue(try vault.delete(first.handle))
        XCTAssertFalse(try vault.delete(first.handle))
        let link = parent.appendingPathComponent("link")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: source)
        XCTAssertThrowsError(try vault.importPicked(link))
        try Data("12345".utf8).write(to: source)
        XCTAssertThrowsError(try vault.importPicked(source))
    }
}
