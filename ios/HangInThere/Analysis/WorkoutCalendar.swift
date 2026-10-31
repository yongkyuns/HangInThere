import Foundation
import WorkoutCore

struct WorkoutCalendarDay: Codable, Equatable, Identifiable, Sendable {
    let day: Int64
    let startEpochMilliseconds: Int64
    let endEpochMilliseconds: Int64
    var id: Int64 { day }
    var date: Date { Date(timeIntervalSince1970: Double(startEpochMilliseconds) / 1000) }
}

enum WorkoutCalendar {
    enum Mode: String, CaseIterable, Sendable {
        case week = "Week", month = "Month"
        var component: Calendar.Component { self == .week ? .weekOfYear : .month }
    }
    static func day(containing date: Date, calendar: Calendar = .current) -> WorkoutCalendarDay {
        let value = try! WorkoutCalendarBridge().day(epochMilliseconds: milliseconds(date), timeZoneId: calendar.timeZone.identifier)
        return convert(value)
    }
    static func days(containing date: Date, mode: Mode, calendar: Calendar = .current) -> [WorkoutCalendarDay] {
        try! WorkoutCalendarBridge().days(anchorEpochMilliseconds: milliseconds(date), timeZoneId: calendar.timeZone.identifier,
            mode: mode.rawValue.lowercased(), firstWeekday: Int32(calendar.firstWeekday)).map(convert)
    }
    static func move(_ date: Date, by value: Int, mode: Mode, calendar: Calendar = .current) -> Date {
        let timestamp = try! WorkoutCalendarBridge().move(anchorEpochMilliseconds: milliseconds(date), timeZoneId: calendar.timeZone.identifier,
            mode: mode.rawValue.lowercased(), firstWeekday: Int32(calendar.firstWeekday), amount: Int32(value))
        return Date(timeIntervalSince1970: Double(timestamp) / 1000)
    }
    private static func milliseconds(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
    private static func convert(_ value: WorkoutCalendarDayRange) -> WorkoutCalendarDay {
        .init(day: value.day, startEpochMilliseconds: value.startEpochMilliseconds, endEpochMilliseconds: value.endEpochMilliseconds)
    }
    static func weekdays(calendar: Calendar = .current) -> [String] {
        (0..<7).map { calendar.shortStandaloneWeekdaySymbols[(calendar.firstWeekday - 1 + $0) % 7] }
    }
}
