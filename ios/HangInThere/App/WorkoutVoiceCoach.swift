import Foundation
import Observation
@preconcurrency import AVFoundation
import WorkoutCore
#if os(iOS)
@preconcurrency import Speech
import UIKit
#endif

struct WorkoutVoiceCommand: Decodable, Equatable, Sendable {
    let action: String
    var exercise: String? = nil
    var load: Double? = nil
    var unit: String? = nil
    static func parse(_ text: String) -> Self? {
        guard let json = WorkoutVoiceBridge().commandJson(text: text) else { return nil }
        return try? JSONDecoder().decode(Self.self, from: Data(json.utf8))
    }
}

/// A presentation channel. Commands call existing UI actions; camera evidence
/// remains the only source of automatically counted reps.
@MainActor @Observable
final class WorkoutVoiceCoach: NSObject {
    static let shared = WorkoutVoiceCoach()
    var enabled: Bool {
        didSet {
            defaults.set(enabled, forKey: "workoutVoiceEnabled")
            if !enabled { speaker.stopSpeaking(at: .immediate) }
        }
    }
    private(set) var listening = false
    private(set) var authorizing = false
    private(set) var status: String?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let speaker = AVSpeechSynthesizer()
    @ObservationIgnored private var countedSet: String?
    @ObservationIgnored private var spokenCount = 0
    @ObservationIgnored private var lastGuidance: String?
    @ObservationIgnored private var lastGuidanceTime = -Double.infinity
    @ObservationIgnored private var contexts: [(id: UUID, handler: (WorkoutVoiceCommand) -> String?)] = []
    #if os(iOS)
    @ObservationIgnored private let audio = AVAudioEngine()
    @ObservationIgnored private var recognizer: SFSpeechRecognizer?
    @ObservationIgnored private var request: SFSpeechAudioBufferRecognitionRequest?
    @ObservationIgnored private var recognition: SFSpeechRecognitionTask?
    @ObservationIgnored private var tapInstalled = false
    @ObservationIgnored private var wantsListening = false
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var authorizationGeneration = 0
    @ObservationIgnored private var pendingCommand: Task<Void, Never>?
    @ObservationIgnored private var restart: Task<Void, Never>?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    #endif

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.bool(forKey: "workoutVoiceEnabled")
        super.init()
        speaker.delegate = self
        #if os(iOS)
        for name in [UIApplication.didEnterBackgroundNotification, AVAudioSession.interruptionNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.stopListening(); self?.speaker.stopSpeaking(at: .immediate) }
            })
        }
        #endif
    }
    func activate(_ id: UUID, handler: @escaping (WorkoutVoiceCommand) -> String?) {
        contexts.removeAll { $0.id == id }
        contexts.append((id, handler))
    }
    func deactivate(_ id: UUID) {
        contexts.removeAll { $0.id == id }
        if contexts.isEmpty { stopListening(); speaker.stopSpeaking(at: .immediate) }
    }
    func speak(_ text: String, interrupt: Bool = true) {
        guard enabled, !contexts.isEmpty, !text.isEmpty else { return }
        if speaker.isSpeaking && !interrupt { return }
        #if os(iOS)
        endRecognition()
        #endif
        if interrupt { speaker.stopSpeaking(at: .immediate) }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice.speechVoices().filter { $0.language == "en-US" }
            .max { $0.quality.rawValue < $1.quality.rawValue } ?? AVSpeechSynthesisVoice(language: "en-US")
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 0.94
        speaker.speak(utterance)
    }
    func stopSpeaking() { speaker.stopSpeaking(at: .immediate) }
    func observedCount(setId: String, count: Int) {
        if countedSet != setId { countedSet = setId; spokenCount = 0 }
        guard count > spokenCount else { return }
        spokenCount = count
        self.count(count)
    }
    func guidance(_ text: String) {
        guard enabled, !contexts.isEmpty else { return }
        let now = ProcessInfo.processInfo.systemUptime
        guard text != lastGuidance, now - lastGuidanceTime >= 8 else { return }
        lastGuidance = text; lastGuidanceTime = now
        speak(text, interrupt: false)
    }
    func count(_ value: Int) { if value > 0 { speak(String(value), interrupt: false) } }
    func completed(count: Int, previous: Int?, saved: Bool = true) {
        speak(WorkoutVoiceBridge().summary(count: Int32(count), previous: Int32(previous ?? -1), saved: saved))
    }
    @discardableResult
    func perform(_ command: WorkoutVoiceCommand) -> String? {
        guard !contexts.isEmpty else { return nil }
        switch command.action {
        case "stopListening": stopListening(); return nil
        case "mute": enabled = false; return "Spoken cues muted."
        default: return contexts.last?.handler(command)
        }
    }
    func stopListening() {
        #if os(iOS)
        wantsListening = false
        authorizationGeneration += 1
        authorizing = false
        endRecognition()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
        listening = false
        status = nil
    }
    func toggleListening() async {
        #if os(iOS)
        if wantsListening || authorizing { stopListening(); return }
        guard !contexts.isEmpty else { return }
        authorizing = true; status = nil
        authorizationGeneration += 1
        let token = authorizationGeneration
        let speechAllowed = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0 == .authorized) }
        }
        guard token == authorizationGeneration else { return }
        let microphoneAllowed = await AVAudioApplication.requestRecordPermission()
        guard token == authorizationGeneration else { return }
        authorizing = false
        guard speechAllowed && microphoneAllowed else {
            status = "Allow microphone and speech access in Settings to use voice commands."
            return
        }
        guard UIApplication.shared.applicationState == .active, !contexts.isEmpty else { return }
        wantsListening = true
        startRecognition()
        #else
        status = "Voice commands are available on iPhone."
        #endif
    }
    #if os(iOS)
    private func endRecognition() {
        generation += 1
        pendingCommand?.cancel(); pendingCommand = nil
        restart?.cancel(); restart = nil
        audio.stop()
        if tapInstalled { audio.inputNode.removeTap(onBus: 0); tapInstalled = false }
        request?.endAudio(); recognition?.cancel()
        request = nil; recognition = nil; listening = false
    }
    private func startRecognition() {
        guard wantsListening, !contexts.isEmpty, UIApplication.shared.applicationState == .active else { return }
        guard !speaker.isSpeaking else { return }
        endRecognition()
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US")),
              recognizer.isAvailable, recognizer.supportsOnDeviceRecognition else {
            wantsListening = false
            status = "On-device English voice recognition is unavailable. Spoken cues still work."
            return
        }
        self.recognizer = recognizer
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .mixWithOthers, .allowBluetooth])
            try session.setActive(true)
            let request = SFSpeechAudioBufferRecognitionRequest()
            request.requiresOnDeviceRecognition = true
            request.shouldReportPartialResults = true
            request.contextualStrings = ["start set", "finish set", "start dips", "start pull-ups", "undo rep", "use twenty kilograms"]
            self.request = request
            let input = audio.inputNode, format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else { throw VoiceError.noMicrophone }
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in request.append(buffer) }
            tapInstalled = true
            let token = generation
            recognition = recognizer.recognitionTask(with: request) { [weak self] result, error in
                let text = result?.bestTranscription.formattedString, final = result?.isFinal == true
                let failed = error != nil
                Task { @MainActor in
                    guard let self, self.generation == token else { return }
                    self.received(text: text, final: final, failed: failed, token: token)
                }
            }
            audio.prepare(); try audio.start()
            listening = true
        } catch {
            stopListening()
            status = "Voice commands couldn’t start. Tap the microphone to retry."
        }
    }
    private func received(text: String?, final: Bool, failed: Bool, token: Int) {
        pendingCommand?.cancel()
        if failed && !final {
            endRecognition(); wantsListening = false
            status = "Voice commands paused. Tap the microphone to retry."
            return
        }
        if let text, let command = WorkoutVoiceCommand.parse(text) {
            pendingCommand = Task { [weak self] in
                if !final { try? await Task.sleep(for: .milliseconds(750)) }
                guard !Task.isCancelled, let self, self.generation == token else { return }
                self.endRecognition()
                if let response = self.perform(command) {
                    self.status = response; self.speak(response)
                }
                self.resumeSoon()
            }
        } else if final || failed {
            endRecognition()
            if failed { wantsListening = false; status = "Voice commands paused. Tap the microphone to retry." }
            else { resumeSoon() }
        }
    }
    private func resumeSoon() {
        restart?.cancel()
        restart = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled, let self, self.wantsListening, !self.speaker.isSpeaking else { return }
            self.startRecognition()
        }
    }
    private enum VoiceError: Error { case noMicrophone }
    #endif
}

extension WorkoutVoiceCoach: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            #if os(iOS)
            self?.resumeSoon()
            #endif
        }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            #if os(iOS)
            self?.resumeSoon()
            #endif
        }
    }
}
