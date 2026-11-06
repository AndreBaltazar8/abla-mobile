#if os(iOS)
import Foundation
import UIKit
import Security
import UniformTypeIdentifiers
import AblaMobileModel

/// Bounded result-bearing services. Every answer returns through event -2;
/// network work is never retried automatically, including an unknown answer.
@MainActor final class IosPlatformServices {
    private let deliver: (Data) -> Bool
    private var active: [Int64: Task<Void, Never>] = [:]
    private var retained: Set<Int64> = []
    private var pending: [(Int64, Data)] = []
    private var pendingBytes = 0
    private var stopped = false
    private let service: String
    private let files = try? HostFiles()
    private let pick: ([String], Bool, @escaping ([URL]?) -> Void) -> Bool
    var canAccept: Bool { !stopped && retained.count < 16 }
    init(deliver: @escaping (Data) -> Bool,
         pick: @escaping ([String], Bool, @escaping ([URL]?) -> Void) -> Bool) {
        self.deliver = deliver
        self.pick = pick
        service = (Bundle.main.bundleIdentifier ?? "org.abla.mobile") + ".secure.v1"
    }
    func stop() {
        stopped = true
        for task in active.values { task.cancel() }
        active.removeAll(); retained.removeAll(); pending.removeAll(); pendingBytes = 0
        files?.clear()
    }
    func flush() {
        while let first = pending.first, deliver(first.1) {
            pending.removeFirst(); pendingBytes -= first.1.count; retained.remove(first.0)
        }
    }
    func receive(id: Int64, method: String, url: String, body: Data) -> Bool {
        guard canAccept, id > 0, retained.insert(id).inserted else { return false }
        switch method {
        case "TIMER":
            guard let milliseconds = Int(url), (100...60000).contains(milliseconds), body.isEmpty else {
                finish(id, error: "invalid_timer"); return true
            }
            active[id] = Task { [weak self] in
                do { try await Task.sleep(for: .milliseconds(milliseconds)) }
                catch { return }
                self?.finish(id, status: 200, body: "timer")
            }
        case "STOREGET", "STORESET", "STOREDEL": secure(id, method: method, key: url, body: body)
        case "PICKFILE", "FILEREAD", "FILEDEL": file(id, method: method, url: url, body: body)
        case "GET", "POST", "SEND":
            guard active.count < 4 else { finish(id, error: "platform_busy"); return true }
            guard let request = makeRequest(method: method, url: url, body: body) else {
                finish(id, error: "invalid_request"); return true
            }
            active[id] = Task { [weak self] in
                let configuration = URLSessionConfiguration.ephemeral
                configuration.httpShouldSetCookies = false
                configuration.httpCookieStorage = nil
                configuration.urlCache = nil
                configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
                configuration.urlCredentialStorage = nil
                configuration.httpMaximumConnectionsPerHost = 4
                configuration.timeoutIntervalForResource = request.timeoutInterval
                let session = URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
                defer { session.invalidateAndCancel() }
                do {
                    let (stream, response) = try await session.bytes(for: request)
                    guard let response = response as? HTTPURLResponse else {
                        self?.finish(id, error: "invalid_response"); return
                    }
                    var data = Data()
                    for try await byte in stream {
                        guard data.count < 384 * 1024 else {
                            self?.finish(id, status: response.statusCode, error: "response_too_large"); return
                        }
                        data.append(byte)
                    }
                    guard !Task.isCancelled else { return }
                    guard let text = String(data: data, encoding: .utf8) else {
                        self?.finish(id, status: response.statusCode, error: "response_not_utf8"); return
                    }
                    var headers: [String: String] = [:]
                    for (name, value) in response.allHeaderFields {
                        let key = String(describing: name).lowercased()
                        let value = String(describing: value)
                        if headers.count < 64 && key.utf8.count <= 128 && value.utf8.count <= 4096 { headers[key] = value }
                    }
                    self?.finish(id, status: response.statusCode, body: text, headers: headers)
                } catch {
                    guard !Task.isCancelled else { return }
                    // Error descriptions can contain URLs, request bodies or
                    // credentials. Only a fixed outcome-unknown code crosses.
                    self?.finish(id, error: "network_failed_outcome_unknown")
                }
            }
        default: finish(id, error: "unsupported_platform_service")
        }
        return true
    }
    private func file(_ id: Int64, method: String, url: String, body: Data) {
        guard let files else { finish(id, error: "files_unavailable"); return }
        if method == "PICKFILE" {
            guard body.isEmpty,
                  let options = try? JSONSerialization.jsonObject(with: Data(url.utf8)) as? [String: Any] else {
                finish(id, error: "invalid_picker"); return
            }
            let requested = options["mime"] as? [String] ?? []
            let mimes = requested.isEmpty ? ["*/*"] : requested
            guard mimes.count <= 16, mimes.allSatisfy({ $0.utf8.count <= 128 }) else {
                finish(id, error: "invalid_picker"); return
            }
            let accepted = pick(mimes, options["multiple"] as? Bool ?? false) { [weak self] urls in
                guard let self, !self.stopped else { return }
                guard let urls else { self.finish(id, status: 200, body: "[]"); return }
                guard urls.count <= 8 else { self.finish(id, error: "too_many_files"); return }
                var imported: [HostFileMetadata] = []
                do {
                    for url in urls {
                        let scoped = url.startAccessingSecurityScopedResource()
                        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
                        imported.append(try files.importPicked(url, mime: mime))
                    }
                    let encoded = try JSONEncoder().encode(imported)
                    self.finish(id, status: 200, body: String(decoding: encoded, as: UTF8.self))
                } catch {
                    for file in imported { _ = try? files.delete(file.handle) }
                    self.finish(id, error: "file_import_failed")
                }
            }
            if !accepted { finish(id, status: 409, error: "picker_busy") }
        } else if method == "FILEREAD" {
            guard let range = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else {
                finish(id, error: "invalid_file_range"); return
            }
            do {
                let data = try files.read(url, offset: (range["offset"] as? Int64) ?? 0,
                                          length: (range["length"] as? Int) ?? 256 * 1024)
                finish(id, status: 200, extras: ["bodyBase64": data.base64EncodedString()])
            } catch { finish(id, error: "file_read_refused") }
        } else {
            guard body.isEmpty else { finish(id, error: "invalid_file_delete"); return }
            do { finish(id, status: try files.delete(url) ? 204 : 404) }
            catch { finish(id, error: "file_delete_failed") }
        }
    }
    private func makeRequest(method: String, url: String, body: Data) -> URLRequest? {
        guard url.utf8.count <= 2048, let parts = URLComponents(string: url),
              parts.scheme == "https", parts.user == nil, parts.password == nil,
              let host = parts.host, !host.isEmpty, let address = parts.url else { return nil }
        var request = URLRequest(url: address)
        request.timeoutInterval = 15
        request.httpMethod = method == "SEND" ? "GET" : method
        if method == "POST" { request.httpBody = body }
        if method == "SEND" {
            guard let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
                  let verb = object["method"] as? String,
                  ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"].contains(verb),
                  object["multipart"] == nil, object["bodyFile"] == nil,
                  object["saveToFile"] as? Bool != true, object["binary"] as? Bool != true else { return nil }
            request.httpMethod = verb
            if let timeout = object["timeoutMs"] as? Int { request.timeoutInterval = Double(max(1000, min(600000, timeout))) / 1000 }
            if let inline = object["bodyBase64"] as? String {
                guard object["body"] == nil, let decoded = Data(base64Encoded: inline), decoded.count <= 8 * 1024 * 1024 else { return nil }
                request.httpBody = decoded
            } else if let inline = object["body"] as? String { request.httpBody = Data(inline.utf8) }
            if let headers = object["headers"] {
                guard let headers = headers as? [String: String], headers.count <= 64 else { return nil }
                for (name, value) in headers {
                    let allowed = "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    guard !name.isEmpty, name.utf8.count <= 128, name.allSatisfy({ allowed.contains($0) }),
                          value.utf8.count <= 8192, !value.contains("\r"), !value.contains("\n"), !value.contains("\0") else { return nil }
                    request.setValue(value, forHTTPHeaderField: name)
                }
            }
        }
        return request
    }
    private func secure(_ id: Int64, method: String, key: String, body: Data) {
        guard !key.isEmpty, key.utf8.count <= 256, body.count <= 16384,
              method == "STORESET" || body.isEmpty else { finish(id, error: "invalid_secure_request"); return }
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: key]
        if method == "STOREGET" {
            var query = query
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
            var found: CFTypeRef?
            let result = SecItemCopyMatching(query as CFDictionary, &found)
            if result == errSecItemNotFound { finish(id, status: 404); return }
            guard result == errSecSuccess, let data = found as? Data, data.count <= 16384,
                  let text = String(data: data, encoding: .utf8) else { finish(id, error: "secure_read_failed"); return }
            finish(id, status: 200, body: text)
        } else if method == "STOREDEL" {
            let result = SecItemDelete(query as CFDictionary)
            finish(id, status: result == errSecSuccess || result == errSecItemNotFound ? 204 : 0,
                   error: result == errSecSuccess || result == errSecItemNotFound ? "" : secureError(result, fallback: "secure_delete_failed"))
        } else {
            guard String(data: body, encoding: .utf8) != nil else { finish(id, error: "invalid_secure_value"); return }
            let attributes: [String: Any] = [kSecValueData as String: body]
            var result = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
            if result == errSecItemNotFound {
                var item = query
                item[kSecValueData as String] = body
                item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
                result = SecItemAdd(item as CFDictionary, nil)
            }
            finish(id, status: result == errSecSuccess ? 204 : 0, error: result == errSecSuccess ? "" : secureError(result, fallback: "secure_write_failed"))
        }
    }
    private func secureError(_ status: OSStatus, fallback: String) -> String {
        // Fixed capability errors, never Security's potentially sensitive
        // human-readable description or the queried account/value.
        if status == errSecMissingEntitlement { return "secure_entitlement_missing" }
        if status == errSecInteractionNotAllowed { return "secure_locked" }
        return fallback
    }
    private func finish(_ id: Int64, status: Int = 0, body: String = "", error: String = "", headers: [String: String] = [:], extras: [String: Any] = [:]) {
        guard !stopped, retained.contains(id) else { return }
        active.removeValue(forKey: id)
        var object: [String: Any] = ["requestId": id, "status": status, "body": body, "error": error, "headers": headers]
        for (key, value) in extras { object[key] = value }
        var data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
        if data.isEmpty || data.count > 512 * 1024 {
            data = (try? JSONSerialization.data(withJSONObject: ["requestId": id, "status": 0, "body": "", "error": "completion_too_large"])) ?? Data()
        }
        if deliver(data) { retained.remove(id) }
        else if pendingBytes + data.count <= 8 * 1024 * 1024 {
            pending.append((id, data)); pendingBytes += data.count
        }
        // At most16 retained requests ×512KiB: the bound above cannot overflow.
    }
}

private final class NoRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    // No challenge override: URLSession applies Apple's normal system trust.
}
#endif
