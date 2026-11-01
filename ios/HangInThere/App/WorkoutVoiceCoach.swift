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
    static func parse(_ text: String, isFinal: Bool = true) -> Self? {
        guard let json = WorkoutVoiceBridge().finalCommandJson(text: text, isFinal: isFinal) else { return nil }
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
    private(set) var microphoneEnabled = false
    private(set) var authorizing = false
    private(set) var status: String?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let authorizeSpeech: @MainActor () async -> Bool
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
    @ObservationIgnored private var audioSessionMode: Bool?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var authorizationGeneration = 0
    @ObservationIgnored private var restart: Task<Void, Never>?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    #endif

    init(defaults: UserDefaults = .standard, authorizeSpeech: (@MainActor () async -> Bool)? = nil) {
        self.defaults = defaults
        self.authorizeSpeech = authorizeSpeech ?? {
            #if os(iOS)
            await withCheckedContinuation { continuation in
                SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0 == .authorized) }
            }
            #else
            false
            #endif
        }
        enabled = defaults.bool(forKey: "workoutVoiceEnabled")
        super.init()
        speaker.delegate = self
        #if os(iOS)
        for name in [UIApplication.didEnterBackgroundNotification, AVAudioSession.interruptionNotification,
                     AVAudioSession.mediaServicesWereLostNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.stopListening(); self?.speaker.stopSpeaking(at: .immediate) }
            })
        }
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] notification in
            let reason = (notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.uintValue
            guard reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
            Task { @MainActor in
                self?.stopListening(); self?.speaker.stopSpeaking(at: .immediate)
                self?.status = "Audio route changed. Tap the microphone to retry."
            }
        })
        #endif
    }
    func activate(_ id: UUID, handler: @escaping (WorkoutVoiceCommand) -> String?) {
        if contexts.last?.id != id { stopListening(); speaker.stopSpeaking(at: .immediate) }
        contexts.removeAll { $0.id == id }
        contexts.append((id, handler))
    }
    func deactivate(_ id: UUID) {
        let wasActive = contexts.last?.id == id
        contexts.removeAll { $0.id == id }
        if wasActive { stopListening(); speaker.stopSpeaking(at: .immediate) }
    }
    func speak(_ text: String, interrupt: Bool = true) {
        guard enabled, !contexts.isEmpty, !text.isEmpty else { return }
        if speaker.isSpeaking && !interrupt { return }
        #if os(iOS)
        endRecognition()
        do {
            try configureAudioSession(microphone: microphoneEnabled)
        } catch {
            stopListening(); status = "Audio is busy. Try the voice controls again."; return
        }
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
        microphoneEnabled = false
        authorizationGeneration += 1
        authorizing = false
        endRecognition()
        releaseAudioSessionIfIdle()
        #endif
        listening = false
        status = nil
    }
    func toggleListening() async {
        #if os(iOS)
        if microphoneEnabled || authorizing { stopListening(); return }
        guard !contexts.isEmpty else { return }
        authorizing = true; status = nil
        authorizationGeneration += 1
        let token = authorizationGeneration
        let speechAllowed = await authorizeSpeech()
        guard token == authorizationGeneration else { return }
        guard speechAllowed else {
            authorizing = false
            status = "Allow speech access in Settings to use voice commands."
            return
        }
        let microphoneAllowed = await AVAudioApplication.requestRecordPermission()
        guard token == authorizationGeneration else { return }
        authorizing = false
        guard speechAllowed && microphoneAllowed else {
            status = "Allow microphone and speech access in Settings to use voice commands."
            return
        }
        guard UIApplication.shared.applicationState == .active, !contexts.isEmpty else { return }
        microphoneEnabled = true
        startRecognition()
        #else
        status = "Voice commands are available on iPhone."
        #endif
    }
    #if os(iOS)
    private func endRecognition() {
        generation += 1
        restart?.cancel(); restart = nil
        audio.stop()
        if tapInstalled { audio.inputNode.removeTap(onBus: 0); tapInstalled = false }
        request?.endAudio(); recognition?.cancel()
        request = nil; recognition = nil; listening = false
    }
    private func startRecognition() {
        guard microphoneEnabled, !contexts.isEmpty, UIApplication.shared.applicationState == .active else { return }
        guard !speaker.isSpeaking else { return }
        endRecognition()
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US")),
              recognizer.isAvailable, recognizer.supportsOnDeviceRecognition else {
            stopListening()
            status = "On-device English voice recognition is unavailable. Spoken cues still work."
            return
        }
        self.recognizer = recognizer
        do {
            try configureAudioSession(microphone: true)
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
        if failed && !final {
            stopListening()
            status = "Voice commands paused. Tap the microphone to retry."
            return
        }
        if let text, let command = WorkoutVoiceCommand.parse(text, isFinal: final) {
            endRecognition()
            if let response = perform(command) { status = response; speak(response) }
            resumeSoon()
        } else if final || failed {
            endRecognition()
            if failed { stopListening(); status = "Voice commands paused. Tap the microphone to retry." }
            else { resumeSoon() }
        }
    }
    private func resumeSoon() {
        restart?.cancel()
        releaseAudioSessionIfIdle()
        restart = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled, let self, self.microphoneEnabled, !self.speaker.isSpeaking else { return }
            self.startRecognition()
        }
    }
    private func configureAudioSession(microphone: Bool) throws {
        guard audioSessionMode != microphone else { return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(microphone ? .playAndRecord : .playback, mode: .default,
            options: microphone ? [.defaultToSpeaker, .mixWithOthers, .allowBluetooth] : [.mixWithOthers])
        try session.setActive(true)
        audioSessionMode = microphone
    }
    private func releaseAudioSessionIfIdle() {
        guard audioSessionMode != nil, !microphoneEnabled, !speaker.isSpeaking else { return }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        audioSessionMode = nil
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
