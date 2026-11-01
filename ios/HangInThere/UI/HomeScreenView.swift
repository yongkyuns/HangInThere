import SwiftUI

/// Fixed illustration colors; UI surfaces use the system appearance below.
enum HangBrand {
    static let ink = Color(red: 0.13, green: 0.24, blue: 0.19)
    static let lime = Color(red: 0.83, green: 0.94, blue: 0.61)
    static let coral = Color(red: 0.93, green: 0.64, blue: 0.52)
    static let cream = Color(red: 0.97, green: 0.96, blue: 0.90)
}

struct HangPalette {
    let isDark: Bool
    init(_ scheme: ColorScheme) { isDark = scheme == .dark }
    var background: Color { isDark ? Color(red: 0.09, green: 0.12, blue: 0.105) : Color(red: 0.965, green: 0.961, blue: 0.937) }
    var foreground: Color { isDark ? HangBrand.cream : HangBrand.ink }
    var secondary: Color { isDark ? Color(red: 0.68, green: 0.75, blue: 0.69) : Color(red: 0.35, green: 0.41, blue: 0.37) }
    var surface: Color { isDark ? Color(red: 0.145, green: 0.20, blue: 0.17) : .white }
    var garden: Color { isDark ? Color(red: 0.17, green: 0.235, blue: 0.19) : Color(red: 0.89, green: 0.93, blue: 0.865) }
    var accent: Color { isDark ? HangBrand.lime : HangBrand.ink }
    var border: Color { foreground.opacity(isDark ? 0.12 : 0.08) }
}

/// Shared styling for app screens and sheets; appearance follows the system.
private struct HangScreenTheme: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    func body(content: Content) -> some View {
        let palette = HangPalette(scheme)
        content
            .scrollContentBackground(.hidden)
            .background(palette.background)
            .foregroundStyle(palette.foreground)
            .tint(palette.accent)
            .fontDesign(.rounded)
            #if os(iOS)
            .toolbarBackground(palette.background, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(scheme, for: .navigationBar)
            #endif
    }
}
private struct HangInputTheme: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    func body(content: Content) -> some View {
        let palette = HangPalette(scheme)
        content.textFieldStyle(.plain).padding(12)
            .background(palette.background, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(palette.border, lineWidth: 1))
    }
}
extension View {
    func hangTheme() -> some View { modifier(HangScreenTheme()) }
    func hangInput() -> some View { modifier(HangInputTheme()) }
}

/// Shared artwork: launch decoration, or exercise shortcuts when actions are supplied.
struct HangIllustrations: View {
    var pullUp = Image("PullUpIllustration")
    var dip = Image("DipIllustration")
    var showsLabels = false
    var onPullUp: (() -> Void)? = nil
    var onDip: (() -> Void)? = nil
    var body: some View {
        HStack(alignment: .bottom, spacing: 20) {
            exercise(pullUp, title: "Pull-ups", identifier: "startPullUpWorkout", action: onPullUp)
            exercise(dip, title: "Dips", identifier: "startDipWorkout", action: onDip)
        }
    }
    @ViewBuilder private func exercise(_ image: Image, title: String, identifier: String, action: (() -> Void)?) -> some View {
        if let action {
            Button(action: action) { artwork(image, title: title, interactive: true).contentShape(Rectangle()) }
                .buttonStyle(.plain).accessibilityLabel("Start \(title.lowercased()) workout")
                .accessibilityIdentifier(identifier)
        } else {
            artwork(image, title: title, interactive: false).accessibilityHidden(true)
        }
    }
    private func artwork(_ image: Image, title: String, interactive: Bool) -> some View {
        VStack(spacing: 8) {
            image.resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            if showsLabels {
                HStack(spacing: 5) {
                    if interactive { Image(systemName: "play.fill").font(.caption2).accessibilityHidden(true) }
                    Text(title).font(.subheadline.weight(.semibold))
                }
            }
        }.frame(maxWidth: .infinity)
    }
}

struct HomeScreenView: View {
    @State private var showsSettings = false
    var onLiveWorkout: () -> Void
    var onStartExercise: (ExerciseCounter.Exercise) -> Void
    var onReviewVideo: () -> Void
    var onHistory: () -> Void
    var onCoach: (() -> Void)? = nil
    var onLogWorkout: (() -> Void)? = nil
    var onStartPushUp: (() -> Void)? = nil
    var onStartSitUp: (() -> Void)? = nil
    var onStartLegRaise: (() -> Void)? = nil
    var onStartLyingLegRaise: (() -> Void)? = nil
    var pullUpArtwork = Image("PullUpIllustration")
    var dipArtwork = Image("DipIllustration")
    var pushUpArtwork = Image("PushUpIllustration")
    var sitUpArtwork = Image("SitUpIllustration")
    var legRaiseArtwork = Image("LegRaiseIllustration")
    var lyingLegRaiseArtwork = Image("LyingLegRaiseIllustration")
    var brandArtwork = Image("BrandIcon")
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    private var palette: HangPalette { HangPalette(colorScheme) }
    private var wideLayout: Bool { verticalSizeClass == .compact && !dynamicTypeSize.isAccessibilitySize }

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            header
            if wideLayout {
                HStack(alignment: .top, spacing: 20) {
                    hero.frame(maxWidth: .infinity)
                    actions.frame(maxWidth: .infinity)
                }
            } else {
                if !dynamicTypeSize.isAccessibilitySize { hero }
                actions
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
        .padding(.bottom, 20)
        .frame(maxWidth: wideLayout ? 900 : 580)
        .frame(maxWidth: .infinity)
        .foregroundStyle(palette.foreground)
        .background(palette.background)
        .fontDesign(.rounded)
        #if os(iOS)
        .sheet(isPresented: $showsSettings) {
            NavigationStack { WorkoutTipSettingsView().toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showsSettings = false } } } }.hangTheme()
        }
        #endif
    }

    private var actions: some View {
        VStack(spacing: 12) {
            if dynamicTypeSize.isAccessibilitySize { exerciseShortcuts }
            liveButton
            if let onLogWorkout {
                secondaryAction("Log a workout", symbol: "square.and.pencil", action: onLogWorkout)
                    .accessibilityIdentifier("openWorkoutLog")
            }
            if let onCoach { WorkoutCoachHomeCard(onOpen: onCoach) }
            let layout = dynamicTypeSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: 12)) : AnyLayout(HStackLayout(spacing: 12))
            layout {
                secondaryAction("Recordings", symbol: "play.rectangle", action: onReviewVideo)
                    .accessibilityIdentifier("reviewRecordings")
                secondaryAction("Your workouts", symbol: "square.stack.3d.up", action: onHistory)
                    .accessibilityIdentifier("workoutHistory")
            }
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            brandArtwork.resizable().scaledToFit()
                .frame(width: 47, height: 47)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .accessibilityHidden(true)
            Text("Hang in there.")
                .font(.system(size: 23, weight: .heavy, design: .rounded))
                .tracking(-0.8)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 0)
            #if os(iOS)
            Button { showsSettings = true } label: { Image(systemName: "gearshape").frame(width: 44, height: 44) }
                .buttonStyle(.plain).accessibilityLabel("Settings").accessibilityIdentifier("openSettings")
            #endif
        }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("One rep at a time.")
                .font(.system(size: wideLayout ? 26 : 30, weight: .heavy, design: .rounded))
                .tracking(-1)
                .lineLimit(2)
            exerciseShortcuts
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .accessibilityElement(children: .contain)
    }

    private var exerciseShortcuts: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? [GridItem(.flexible())] : [GridItem(.flexible()), GridItem(.flexible())]
        return LazyVGrid(columns: layout, spacing: 12) {
            exerciseShortcut("Pull-ups", artwork: pullUpArtwork, preview: false, identifier: "startPullUpWorkout") { onStartExercise(.pullUp) }
            exerciseShortcut("Dips", artwork: dipArtwork, preview: false, identifier: "startDipWorkout") { onStartExercise(.dip) }
            if let onStartPushUp {
                exerciseShortcut("Push-ups", artwork: pushUpArtwork, preview: true, identifier: "startPushUpWorkout", action: onStartPushUp)
            }
            if let onStartSitUp {
                exerciseShortcut("Sit-ups", artwork: sitUpArtwork, preview: true, identifier: "startSitUpWorkout", action: onStartSitUp)
            }
            if let onStartLegRaise {
                exerciseShortcut("Hanging raises", artwork: legRaiseArtwork, preview: true, identifier: "startLegRaiseWorkout", action: onStartLegRaise)
            }
            if let onStartLyingLegRaise {
                exerciseShortcut("Lying raises", artwork: lyingLegRaiseArtwork, preview: true, identifier: "startLyingLegRaiseWorkout", action: onStartLyingLegRaise)
            }
        }
    }

    private func exerciseShortcut(_ title: String, artwork: Image, preview: Bool, identifier: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                artwork.resizable().scaledToFit()
                    .frame(maxWidth: .infinity).frame(height: 140)
                    .accessibilityHidden(true)
                HStack(alignment: .center, spacing: 8) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(title).font(.headline)
                        if preview { Text("Preview").font(.caption.weight(.medium)).foregroundStyle(palette.secondary) }
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "play.fill").font(.caption).accessibilityHidden(true)
                }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(16)
                .background(palette.surface, in: RoundedRectangle(cornerRadius: 20))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(palette.border))
                .contentShape(RoundedRectangle(cornerRadius: 20))
        }.buttonStyle(.plain).accessibilityLabel("Start \(title.lowercased()) workout")
            .accessibilityIdentifier(identifier)
    }

    private var liveButton: some View {
        Button(action: onLiveWorkout) {
            HStack(spacing: 13) {
                Image(systemName: "viewfinder")
                    .font(.system(size: 23, weight: .medium))
                    .frame(width: 48, height: 48)
                    .background(HangBrand.ink.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
                Text("Start workout").font(.title3.bold())
                Spacer(minLength: 2)
                Image(systemName: "arrow.right")
                    .font(.system(size: 18, weight: .semibold))
            }
            .foregroundStyle(HangBrand.ink)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(HangBrand.lime, in: RoundedRectangle(cornerRadius: 23))
            .contentShape(RoundedRectangle(cornerRadius: 23))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("startLiveWorkout")
        .accessibilityLabel("Start live workout")
    }

    private func secondaryAction(_ title: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 13) {
                HStack {
                    Image(systemName: symbol)
                        .font(.system(size: 22, weight: .medium))
                        .foregroundStyle(palette.foreground)
                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(palette.secondary)
                }
                Text(title).font(.subheadline.bold())
            }
            .frame(maxWidth: .infinity, minHeight: 68, alignment: .topLeading)
            .padding(16)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: 23))
            .contentShape(RoundedRectangle(cornerRadius: 23))
        }
        .buttonStyle(.plain)
    }


}

struct WorkoutCameraGuideView: View {
    var exercise: ExerciseCounter.Exercise? = nil
    var onContinue: () -> Void
    var onCancel: () -> Void
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Image(systemName: "person.fill.viewfinder").font(.system(size: 56)).foregroundStyle(.teal)
                    .accessibilityHidden(true)
                Text("A clear view. A good start.").font(.largeTitle.bold())
                if exercise == .lyingLegRaise {
                    instruction("rectangle.landscape.rotate", "Low, level phone", "Use landscape beside you, aimed level.")
                    instruction("viewfinder", "Side view", "Keep shoulder, hip and knees visible. Include feet when possible.")
                    instruction("checkmark.circle", "Lie back · legs extended", "Wait for Ready, lift your legs, then lower fully. Keep your torso down.")
                } else if exercise == .legRaise {
                    instruction("iphone", "Steady, level phone", "Use landscape with a side or three-quarter view.")
                    instruction("viewfinder", "Hands to knees visible", "Keep one shoulder, hand, hip and knee in view.")
                    instruction("checkmark.circle", "Hang with legs down", "Hold still until Ready, lift your legs, then lower fully.")
                } else if exercise == .pushUp || exercise == .sitUp {
                    instruction("rectangle.landscape.rotate", "Low, level phone", "Use landscape and keep it steady.")
                    instruction("viewfinder", exercise == .sitUp ? "Side view" : "Front or side view",
                        exercise == .sitUp ? "Show shoulder, hip and bent knees." : "Keep shoulders, elbows, hands and hips visible.")
                    instruction("checkmark.circle", "Hold your starting pose",
                        exercise == .sitUp ? "Lie back with knees bent. Wait for Ready." : "Hold a straight plank. Wait for Ready.")
                } else {
                    instruction("iphone", "Phone at chest height", "Keep it level and steady.")
                    instruction("person.fill.turn.right", "Face the camera, or face away", "Use a front or rear view.")
                    instruction("figure.arms.open", "Both arms in frame", "Keep shoulders, elbows and hands visible.")
                }
                WorkoutTrainingTipCard(exercise: exercise?.rawValue, moment: "prepare")
            }.padding(24).frame(maxWidth: 540, alignment: .leading).frame(maxWidth: .infinity)
        }
        .safeAreaInset(edge: .bottom) {
            Button(action: onContinue) {
                Label("Open camera", systemImage: "camera.fill").font(.headline)
                    .frame(maxWidth: .infinity).padding(17).foregroundStyle(HangBrand.ink)
                    .background(HangBrand.lime, in: RoundedRectangle(cornerRadius: 18))
            }.buttonStyle(.plain).padding(20).background(.regularMaterial)
                .accessibilityIdentifier("completeCameraGuide")
        }
        .background(HangPalette(scheme).background).fontDesign(.rounded)
        .navigationTitle("Before your first workout")
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Back", action: onCancel) } }
    }
    private func instruction(_ symbol: String, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: symbol).font(.title).frame(width: 42).foregroundStyle(.teal).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 5) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
        }.accessibilityElement(children: .combine)
    }
}

struct HangLaunchLockup: View {
    var pullUpArtwork = Image("PullUpIllustration")
    var dipArtwork = Image("DipIllustration")
    @Environment(\.colorScheme) private var colorScheme
    var body: some View {
        VStack(spacing: 14) {
            HangIllustrations(pullUp: pullUpArtwork, dip: dipArtwork).frame(width: 280, height: 230)
            Text("Hang in there.")
                .font(.system(size: 30, weight: .heavy, design: .rounded))
                .tracking(-1)
            Text("One rep at a time.")
                .font(.system(size: 14, weight: .medium, design: .rounded))
                .foregroundStyle(HangPalette(colorScheme).secondary)
        }
        .foregroundStyle(HangPalette(colorScheme).foreground)
        .frame(width: 280, height: 350)
    }
}

struct BrandLaunchView: View {
    var pullUpArtwork = Image("PullUpIllustration")
    var dipArtwork = Image("DipIllustration")
    @Environment(\.colorScheme) private var colorScheme
    var body: some View {
        ZStack {
            HangPalette(colorScheme).background.ignoresSafeArea()
            HangLaunchLockup(pullUpArtwork: pullUpArtwork, dipArtwork: dipArtwork)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }
}
