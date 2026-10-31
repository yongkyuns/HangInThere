import Foundation
import Testing
@testable import HangInThere

struct WorkoutCalendarTests {
    private func calendar(firstWeekday: Int = 1) -> Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "America/Toronto")!
        value.locale = Locale(identifier: "en_CA")
        value.firstWeekday = firstWeekday
        return value
    }
    private func date(_ year: Int, _ month: Int, _ day: Int, calendar: Calendar) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day, hour: 12))!
    }
    @Test(arguments: [1, 2]) func leapMonthIncludesWholeWeeksAndMatchesWeekdayHeaders(firstWeekday: Int) throws {
        let cal = calendar(firstWeekday: firstWeekday)
        let days = WorkoutCalendar.days(containing: date(2024, 2, 29, calendar: cal), mode: .month, calendar: cal)
        #expect(days.count == 35)
        #expect(cal.component(.weekday, from: days[0].date) == firstWeekday)
        #expect(days.filter { cal.component(.month, from: $0.date) == 2 }.count == 29)
        #expect(WorkoutCalendar.weekdays(calendar: cal).first == cal.shortStandaloneWeekdaySymbols[firstWeekday - 1])
        #expect(zip(days, days.dropFirst()).allSatisfy { $0.endEpochMilliseconds == $1.startEpochMilliseconds && $0.day + 1 == $1.day })
    }
    @Test func navigationCrossesYearsAndAnchorsMonthWithoutSkippingFebruary() {
        let cal = calendar()
        let january = date(2024, 1, 31, calendar: cal)
        let next = WorkoutCalendar.move(january, by: 1, mode: .month, calendar: cal)
        #expect(cal.component(.month, from: next) == 2)
        #expect(cal.component(.day, from: next) == 1)
        let previous = WorkoutCalendar.move(january, by: -1, mode: .month, calendar: cal)
        #expect(cal.component(.year, from: previous) == 2023)
        #expect(cal.component(.month, from: previous) == 12)
        let week = WorkoutCalendar.days(containing: date(2024, 1, 1, calendar: cal), mode: .week, calendar: cal)
        #expect(week.count == 7)
        #expect(cal.component(.year, from: week[0].date) == 2023)
    }
    @Test(arguments: [(3, 10, 23), (11, 3, 25)]) func dayBoundsFollowDaylightSaving(month: Int, day: Int, hours: Int) {
        let cal = calendar()
        let target = WorkoutCalendar.day(containing: date(2024, month, day, calendar: cal), calendar: cal)
        #expect(target.endEpochMilliseconds - target.startEpochMilliseconds == Int64(hours) * 3_600_000)
        let nextDate = Date(timeIntervalSince1970: Double(target.endEpochMilliseconds) / 1000)
        let next = WorkoutCalendar.day(containing: nextDate, calendar: cal)
        #expect(next.day == target.day + 1)
        #expect(next.startEpochMilliseconds == target.endEpochMilliseconds)
    }
    @Test func historicalMarkersAndSelectedDayUseSameNativeBridgeBounds() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root)
        let cal = calendar()
        let target = WorkoutCalendar.day(containing: date(2024, 11, 3, calendar: cal), calendar: cal)
        let log = LoggedWorkout(epochMilliseconds: target.endEpochMilliseconds - 1,
            sets: [.init(exercise: "Bench press", reps: 5, load: 165)])
        let next = LoggedWorkout(epochMilliseconds: target.endEpochMilliseconds,
            sets: [.init(exercise: "EZ curls", reps: 7, load: 75)])
        try await store.saveWorkoutLog(log); try await store.saveWorkoutLog(next)
        let markers = try await store.libraryCalendarDays([target])
        #expect(markers.count == 1); #expect(markers[0].workoutIds == [log.id])
        #expect(markers[0].totalReps == 5)
        let json = try await store.libraryJourneyPage(day: target.day, cursor: nil, range: target)
        let page = try JSONDecoder().decode(JourneyPage.self, from: Data(json.utf8))
        #expect(page.workouts.map(\.id) == [log.id])
    }
}
