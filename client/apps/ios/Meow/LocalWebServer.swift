import Foundation
import Network

/// 进程内只读静态服务：把 Bundle 里的 Web/ 目录用 http://127.0.0.1:<port> 暴露给 WKWebView。
///
/// 为什么不用 loadFileURL：Vite 产物是 ES module，file:// 下会被 CORS 拒绝；
/// 而且 localStorage 在 file:// 的语义也不稳定（TV 的 build.gradle 记录过同款坑）。
final class LocalWebServer {
    private final class StartBox {
        var port: UInt16 = 0
        var error: Error?
    }

    private let queue = DispatchQueue(label: "meow.ios.web-server")
    private let root: URL
    private var listener: NWListener?
    private(set) var port: UInt16 = 0

    init() {
        let bundleRoot = Bundle.main.resourceURL ?? Bundle.main.bundleURL
        root = bundleRoot.appendingPathComponent("Web", isDirectory: true)
    }

    func start() throws -> UInt16 {
        guard FileManager.default.fileExists(atPath: root.path) else {
            throw NSError(
                domain: "MeowWebServer",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "找不到内嵌 Web 目录"]
            )
        }

        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        parameters.requiredLocalEndpoint = .hostPort(
            host: NWEndpoint.Host("127.0.0.1"),
            port: .any
        )

        let listener = try NWListener(using: parameters)
        self.listener = listener

        let box = StartBox()
        let semaphore = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                box.port = listener.port?.rawValue ?? 0
                semaphore.signal()
            case .failed(let error):
                box.error = error
                semaphore.signal()
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.handle(connection)
        }
        listener.start(queue: queue)

        _ = semaphore.wait(timeout: .now() + 5)
        if let error = box.error {
            throw error
        }
        guard box.port > 0 else {
            throw NSError(
                domain: "MeowWebServer",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: "本地端口没有分配成功"]
            )
        }
        port = box.port
        return box.port
    }

    private func handle(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(on: connection, buffer: Data())
    }

    private func receive(on connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else {
                connection.cancel()
                return
            }
            var buffer = buffer
            if let data {
                buffer.append(data)
            }
            if let headerEnd = buffer.range(of: Data("\r\n\r\n".utf8)) {
                let header = String(decoding: buffer[..<headerEnd.lowerBound], as: UTF8.self)
                self.respond(header: header, on: connection)
                return
            }
            if isComplete || error != nil || buffer.count > 1_048_576 {
                connection.cancel()
                return
            }
            self.receive(on: connection, buffer: buffer)
        }
    }

    private func respond(header: String, on connection: NWConnection) {
        let requestLine = header.components(separatedBy: "\r\n").first ?? ""
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else {
            send(status: "400 Bad Request", body: Data("Bad Request".utf8), contentType: "text/plain; charset=utf-8", on: connection)
            return
        }

        let target = String(parts[1])
        let rawPath = target.components(separatedBy: "?").first ?? "/"
        let decodedPath = rawPath.removingPercentEncoding ?? rawPath

        // 后端请求不进静态服务：让前端明确拿到 404，避免把 index.html 当 JSON 解析。
        if decodedPath == "/api" || decodedPath.hasPrefix("/api/") {
            send(status: "404 Not Found", body: Data("Not Found".utf8), contentType: "text/plain; charset=utf-8", on: connection)
            return
        }

        let relative: String
        if decodedPath == "/" {
            relative = "index.html"
        } else if decodedPath.hasPrefix("/") {
            relative = String(decodedPath.dropFirst())
        } else {
            relative = decodedPath
        }

        let fileURL = root.appendingPathComponent(relative).standardizedFileURL
        let rootPath = root.standardizedFileURL.path
        let filePath = fileURL.path
        guard filePath == rootPath || filePath.hasPrefix(rootPath + "/") else {
            send(status: "403 Forbidden", body: Data("Forbidden".utf8), contentType: "text/plain; charset=utf-8", on: connection)
            return
        }

        var isDirectory: ObjCBool = false
        if FileManager.default.fileExists(atPath: filePath, isDirectory: &isDirectory),
           !isDirectory.boolValue,
           let data = try? Data(contentsOf: fileURL) {
            send(status: "200 OK", body: data, contentType: mimeType(for: fileURL.pathExtension), on: connection)
            return
        }

        // 有扩展名的缺失资源直接 404（避免把 HTML 当 JS/CSS 返回）；
        // 无扩展名的路径回 index.html，兼容前端路由。
        if !fileURL.pathExtension.isEmpty {
            send(status: "404 Not Found", body: Data("Not Found".utf8), contentType: "text/plain; charset=utf-8", on: connection)
            return
        }

        let indexURL = root.appendingPathComponent("index.html")
        if let data = try? Data(contentsOf: indexURL) {
            send(status: "200 OK", body: data, contentType: "text/html; charset=utf-8", on: connection)
        } else {
            send(status: "404 Not Found", body: Data("Not Found".utf8), contentType: "text/plain; charset=utf-8", on: connection)
        }
    }

    private func send(status: String, body: Data, contentType: String, on connection: NWConnection) {
        let headers = [
            "HTTP/1.1 \(status)",
            "Content-Type: \(contentType)",
            "Content-Length: \(body.count)",
            "Cache-Control: no-store",
            "Connection: close",
            "",
            "",
        ].joined(separator: "\r\n")
        var response = Data(headers.utf8)
        response.append(body)
        connection.send(content: response, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private func mimeType(for pathExtension: String) -> String {
        switch pathExtension.lowercased() {
        case "html", "htm":
            return "text/html; charset=utf-8"
        case "js", "mjs":
            return "text/javascript; charset=utf-8"
        case "css":
            return "text/css; charset=utf-8"
        case "json", "map":
            return "application/json; charset=utf-8"
        case "webmanifest":
            return "application/manifest+json; charset=utf-8"
        case "txt":
            return "text/plain; charset=utf-8"
        case "png":
            return "image/png"
        case "jpg", "jpeg":
            return "image/jpeg"
        case "gif":
            return "image/gif"
        case "svg":
            return "image/svg+xml"
        case "ico":
            return "image/x-icon"
        case "woff":
            return "font/woff"
        case "woff2":
            return "font/woff2"
        case "ttf":
            return "font/ttf"
        case "otf":
            return "font/otf"
        case "wasm":
            return "application/wasm"
        case "mp4":
            return "video/mp4"
        case "m3u8":
            return "application/vnd.apple.mpegurl"
        case "ts":
            return "video/mp2t"
        default:
            return "application/octet-stream"
        }
    }
}
