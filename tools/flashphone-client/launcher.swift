import AppKit
final class Launcher: NSObject, NSApplicationDelegate {
    private var worker: Process?
    func applicationDidFinishLaunching(_ notification: Notification) {
        let home = FileManager.default.homeDirectoryForCurrentUser
        let command = home.appendingPathComponent("Library/Application Support/Flash phone/bin/flashphone.py")
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/opt/homebrew/bin/python3")
        task.arguments = [command.path, "open-dashboard"]
        task.standardOutput = FileHandle.nullDevice
        task.standardError = FileHandle.nullDevice
        task.terminationHandler = { process in DispatchQueue.main.async {
            if process.terminationStatus != 0 {
                let alert = NSAlert()
                alert.messageText = "Flash phone 控制台暂未打开"
                alert.informativeText = "请确认 Mac 网络可用，然后重新打开。已有电话任务不会被重新执行。"
                alert.runModal()
            }
            NSApplication.shared.terminate(nil)
        } }
        do { worker = task; try task.run() }
        catch { NSApplication.shared.terminate(nil) }
    }
}
let app = NSApplication.shared
let delegate = Launcher()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
