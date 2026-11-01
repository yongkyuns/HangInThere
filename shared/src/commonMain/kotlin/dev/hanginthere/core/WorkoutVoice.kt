package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Finite, whole-utterance commands. Voice never manufactures camera reps. */
@Serializable data class WorkoutVoiceCommand(val action: String, val exercise: String? = null,
    val load: Double? = null, val unit: String? = null)
object WorkoutVoice {
    fun command(text: String, isFinal: Boolean = true): WorkoutVoiceCommand? {
        if (!isFinal || text.length > 160) return null
        val words = text.lowercase().replace(Regex("(?<=[a-z])-(?=[a-z])"), " ").trim().trimEnd('.', '!', '?').split(Regex("\\s+"))
        val phrase = words.joinToString(" ").removePrefix("please ").removeSuffix(" please")
        val action = when (phrase) {
            "start", "start set", "start a set", "begin set", "begin a set" -> "start"
            "finish set", "finish the set", "end set", "end the set", "stop set" -> "finish"
            "undo", "undo that", "undo rep", "undo that rep", "undo last rep" -> "undo"
            "add rep", "count rep", "one more rep" -> "add"
            "next set" -> "next"
            "mute", "mute voice" -> "mute"
            "stop listening", "microphone off" -> "stopListening"
            "clear weight", "no weight" -> "clearWeight"
            else -> null
        }
        if (action != null) return WorkoutVoiceCommand(action)
        val exercise = when (phrase) {
            "start pull ups", "start pullups", "start chin ups", "start chinups" -> "pullUp"
            "start dips" -> "dip"
            "start push ups", "start pushups" -> "pushUp"
            "start sit ups", "start situps" -> "sitUp"
            "start lying raises", "start lying leg raises", "start floor leg raises" -> "lyingLegRaise"
            "start leg raises", "start hanging raises", "start hanging leg raises", "start knee raises" -> "legRaise"
            else -> null
        }
        if (exercise != null) return WorkoutVoiceCommand("start", exercise = exercise)
        val match = Regex("(?:use|set weight to|weight) (.+) (kilograms?|kilos?|kg|pounds?|lbs?|lb)").matchEntire(phrase) ?: return null
        val load = number(match.groupValues[1]) ?: return null
        val unit = if (match.groupValues[2].startsWith("k")) "kg" else "lb"
        return WorkoutVoiceCommand("weight", load = load, unit = unit)
            .takeIf { WorkoutLogPolicy.validSet("voice", 0, load, unit) }
    }
    private fun number(text: String): Double? {
        text.toDoubleOrNull()?.let { return it.takeIf(Double::isFinite) }
        val units = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        val tens = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        val pieces = text.split(" point ")
        if (pieces.size > 2) return null
        fun underHundred(words: List<String>): Int? {
            if (words.size == 1) return units.indexOf(words[0]).takeIf { it >= 0 }
                ?: tens.indexOf(words[0]).takeIf { it >= 0 }?.let { (it + 2) * 10 }
            if (words.size != 2) return null
            val ten = tens.indexOf(words[0]).takeIf { it >= 0 } ?: return null
            val digit = units.indexOf(words[1]).takeIf { it in 1..9 } ?: return null
            return (ten + 2) * 10 + digit
        }
        val integer = pieces[0].split(' ')
        val value = if (integer.size >= 2 && integer[1] == "hundred") {
            val hundreds = units.indexOf(integer[0]).takeIf { it in 1..9 } ?: return null
            val rest = integer.drop(2).let { if (it.firstOrNull() == "and") it.drop(1) else it }
            hundreds * 100 + if (rest.isEmpty()) 0 else underHundred(rest) ?: return null
        } else underHundred(integer) ?: return null
        val fraction = pieces.getOrNull(1)?.split(' ')?.map { word ->
            units.indexOf(word).takeIf { it in 0..9 } ?: return null
        }?.joinToString("")
        return if (fraction != null) "$value.$fraction".toDoubleOrNull() else value.toDouble()
    }
    fun summary(count: Int, previous: Int?, saved: Boolean = true): String {
        val result = "$count ${if (count == 1) "rep" else "reps"}${if (saved) " saved." else "."}"
        return if (previous != null && previous >= 0 && count > previous) "$result ${count - previous} more than your previous set." else result
    }
}
class WorkoutVoiceBridge {
    fun commandJson(text: String): String? = WorkoutVoice.command(text)?.let { WorkoutRecordCodec.json.encodeToString(it) }
    fun finalCommandJson(text: String, isFinal: Boolean): String? = WorkoutVoice.command(text, isFinal)?.let { WorkoutRecordCodec.json.encodeToString(it) }
    fun summary(count: Int, previous: Int, saved: Boolean): String = WorkoutVoice.summary(count, previous.takeIf { it >= 0 }, saved)
}
