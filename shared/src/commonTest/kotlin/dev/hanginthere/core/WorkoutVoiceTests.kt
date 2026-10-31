package dev.hanginthere.core

import kotlin.test.*

class WorkoutVoiceTests {
    @Test fun commandsAreWholeUtterancesWithExplicitWeightUnits() {
        assertEquals(WorkoutVoiceCommand("start", "dip"), WorkoutVoice.command("Please start dips."))
        assertEquals(WorkoutVoiceCommand("start", "pushUp"), WorkoutVoice.command("start push-ups"))
        assertEquals("finish", WorkoutVoice.command("finish set")?.action)
        assertEquals("undo", WorkoutVoice.command("undo that rep")?.action)
        assertEquals(WorkoutVoiceCommand("weight", load = 20.5, unit = "kg"), WorkoutVoice.command("use twenty point five kilograms"))
        assertEquals(WorkoutVoiceCommand("weight", load = 165.0, unit = "lb"), WorkoutVoice.command("set weight to 165 pounds"))
        assertEquals(WorkoutVoiceCommand("weight", load = 105.0, unit = "lb"), WorkoutVoice.command("use one hundred and five pounds"))
        for (text in listOf("don't finish set", "I said start dips yesterday", "use 20", "use -2 kg", "use one two kilograms", "use NaN kg", "use 2001 kg", "finish set and start dips"))
            assertNull(WorkoutVoice.command(text), text)
    }
    @Test fun completionUsesActualCountsWithoutInferringFatigue() {
        assertEquals("1 rep saved.", WorkoutVoice.summary(1, null))
        assertEquals("5 reps saved. 1 more than your previous set.", WorkoutVoice.summary(5, 4))
        assertEquals("5 reps saved.", WorkoutVoice.summary(5, 6))
        assertEquals("5 reps.", WorkoutVoice.summary(5, null, saved = false))
    }
}
