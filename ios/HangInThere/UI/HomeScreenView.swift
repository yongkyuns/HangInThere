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
}

/// A soft H / pull-up rig, shared by the icon and the home wordmark.
struct HangBrandMark: Shape {
    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width, rect.height) / 100
        let x = rect.midX - 50 * scale, y = rect.midY - 50 * scale
        var path = Path()
        for bar in [CGRect(x: 10, y: 18, width: 80, height: 13),
                    CGRect(x: 24, y: 18, width: 14, height: 65),
                    CGRect(x: 62, y: 18, width: 14, height: 65),
                    CGRect(x: 25, y: 48, width: 50, height: 14)] {
            path.addRoundedRect(in: CGRect(x: x + bar.minX * scale, y: y + bar.minY * scale,
                width: bar.width * scale, height: bar.height * scale), cornerSize: CGSize(width: 6.5 * scale, height: 6.5 * scale))
        }
        return path
    }
}

/// Shared, background-free exercise artwork. These are decorative, not tracking feedback.
struct HangIllustrations: View {
    var pullUp = Image("PullUpIllustration")
    var dip = Image("DipIllustration")
    var showsLabels = false
    var body: some View {
        HStack(alignment: .bottom, spacing: 20) {
            exercise(pullUp, title: "Pull-ups")
            exercise(dip, title: "Dips")
        }
        .accessibilityHidden(true)
    }
    private func exercise(_ image: Image, title: String) -> some View {
        VStack(spacing: 8) {
            image.resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            if showsLabels { Text(title).font(.subheadline.weight(.semibold)) }
        }
        .frame(maxWidth: .infinity)
    }
}

struct HomeScreenView: View {
    var onLiveWorkout: () -> Void
    var onImportVideo: () -> Void
    var onHistory: () -> Void
    var pullUpArtwork = Image("PullUpIllustration")
    var dipArtwork = Image("DipIllustration")
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
            placementGuide
            Label("Just you, your camera & your reps. Stays on device.", systemImage: "lock")
                .font(.caption.weight(.medium))
                .foregroundStyle(palette.secondary)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 8)
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
        .padding(.bottom, 20)
        .frame(maxWidth: wideLayout ? 900 : 580)
        .frame(maxWidth: .infinity)
        .foregroundStyle(palette.foreground)
        .background(palette.background)
        .fontDesign(.rounded)
    }

    private var actions: some View {
        VStack(spacing: 12) {
            liveButton
            let layout = dynamicTypeSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: 12)) : AnyLayout(HStackLayout(spacing: 12))
            layout {
                secondaryAction("Review video", detail: "A fresh look at your reps", symbol: "play.rectangle", action: onImportVideo)
                    .accessibilityIdentifier("importVideo")
                secondaryAction("Your workouts", detail: "See how far you've come", symbol: "square.stack.3d.up", action: onHistory)
                    .accessibilityIdentifier("workoutHistory")
            }
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            ZStack {
                HangBrandMark().fill(HangBrand.ink)
                Circle().fill(HangBrand.coral).frame(width: 5.4, height: 5.4).offset(y: -5)
            }
                .frame(width: 35, height: 35)
                .padding(6)
                .background(HangBrand.lime, in: RoundedRectangle(cornerRadius: 16))
                .accessibilityHidden(true)
            Text("Hang in there.")
                .font(.system(size: 23, weight: .heavy, design: .rounded))
                .tracking(-0.8)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 0)
        }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("One rep at a time.")
                .font(.system(size: wideLayout ? 26 : 30, weight: .heavy, design: .rounded))
                .tracking(-1)
                .lineLimit(2)
            HangIllustrations(pullUp: pullUpArtwork, dip: dipArtwork, showsLabels: true)
                .frame(height: wideLayout ? 190 : 215)
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("One rep at a time. Pull-ups and dips.")
    }

    private var liveButton: some View {
        Button(action: onLiveWorkout) {
            HStack(spacing: 13) {
                Image(systemName: "viewfinder")
                    .font(.system(size: 23, weight: .medium))
                    .frame(width: 48, height: 48)
                    .background(HangBrand.ink.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
                VStack(alignment: .leading, spacing: 4) {
                    Text("Let's get moving").font(.title3.bold())
                    if !dynamicTypeSize.isAccessibilitySize {
                        Text("Start a live workout")
                            .font(.caption.weight(.medium))
                    }
                }
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

    private func secondaryAction(_ title: String, detail: String, symbol: String, action: @escaping () -> Void) -> some View {
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
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.subheadline.bold())
                    if !dynamicTypeSize.isAccessibilitySize {
                        Text(detail).font(.caption.weight(.medium)).foregroundStyle(palette.secondary)
                    }
                }
            }
            .frame(maxWidth: .infinity, minHeight: 90, alignment: .topLeading)
            .padding(16)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: 23))
            .contentShape(RoundedRectangle(cornerRadius: 23))
        }
        .buttonStyle(.plain)
    }

    private var placementGuide: some View {
        VStack(alignment: .leading, spacing: 13) {
            Text("Give your camera a good view")
                .font(.subheadline.bold())
                .foregroundStyle(palette.secondary)
            let layout = dynamicTypeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12)) : AnyLayout(HStackLayout(alignment: .top, spacing: 8))
            layout {
                placementItem("iphone", title: "Chest-height\nphone")
                placementItem("person.fill.viewfinder", title: "Front or\nrear view")
                placementItem("figure.arms.open", title: "Both arms\nin frame")
            }
        }
    }

    private func placementItem(_ symbol: String, title: String) -> some View {
        HStack(alignment: .top, spacing: 7) {
            Image(systemName: symbol).font(.system(size: 19, weight: .regular)).frame(width: 23)
            Text(title).font(.caption.weight(.medium)).lineSpacing(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
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
