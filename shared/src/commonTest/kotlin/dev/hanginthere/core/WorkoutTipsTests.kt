package dev.hanginthere.core

import kotlin.test.*

class WorkoutTipsTests {
    @Test fun everyTipHasTraceableSourcesAndEveryExerciseHasGuidance() {
        val catalog = WorkoutTips.catalog
        assertEquals(catalog.sources.size, catalog.sources.map { it.id }.distinct().size)
        assertEquals(catalog.tips.size, catalog.tips.map { it.id }.distinct().size)
        val sources = catalog.sources.associateBy { it.id }
        catalog.tips.forEach { tip ->
            assertTrue(tip.sourceIds.isNotEmpty(), tip.id)
            tip.sourceIds.forEach { id ->
                val source = assertNotNull(sources[id], "$id in ${tip.id}")
                assertTrue(source.url.startsWith("https://"))
                assertTrue(source.checkedOn.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            }
        }
        assertEquals(sources.keys, catalog.tips.flatMap { it.sourceIds }.toSet())
        for (exercise in listOf("pullUp", "dip", "pushUp", "sitUp")) {
            assertTrue(catalog.tips.any { exercise in it.exercises }, exercise)
        }
    }

    @Test fun exerciseAliasesIncludeGeneralGuidanceButExcludeOtherMovements() {
        assertEquals(WorkoutTips.query("pullUp"), WorkoutTips.query("Chin-ups"))
        val push = WorkoutTips.query("Push-ups")
        assertTrue(push.any { it.id == "warm-up" })
        assertTrue(push.any { it.id == "push-line" })
        assertFalse(push.any { it.exercises.isNotEmpty() && "pushUp" !in it.exercises })
        assertTrue(WorkoutTips.query("Bench press").all { it.exercises.isEmpty() })
    }

    @Test fun combinedSearchAndTopicFiltersDoNotLeakUnrelatedTips() {
        assertEquals(listOf("pull-brace"), WorkoutTips.query("Pull-ups", "Technique", " SWING brace ").map { it.id })
        assertTrue(WorkoutTips.query(category = "Nutrition").all { it.category == "Nutrition" })
        assertTrue(WorkoutTips.query(search = "no-such-topic").isEmpty())
        assertTrue(WorkoutTips.query(category = "missing").isEmpty())
    }

    @Test fun featuredTipsAreStableAppropriateAndNeverInferSymptoms() {
        for (exercise in listOf(null, "pullUp", "dip", "pushUp", "sitUp", "Bench press")) {
            for (moment in listOf("prepare", "review", "plan")) {
                for (day in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                    val tip = assertNotNull(WorkoutTips.featured(exercise, moment, day))
                    assertEquals(tip, WorkoutTips.featured(exercise, moment, day))
                    assertTrue(tip in WorkoutTips.query(exercise))
                    assertTrue(moment in tip.moments)
                    assertNotEquals("Safety", tip.category) // Safety is browsable, not a diagnosis.
                }
            }
        }
        assertNull(WorkoutTips.featured("dip", "during-rep", 0))
    }

    @Test fun nativeBridgeExportsCatalogFiltersAndMissingTipWithoutSchemaLoss() {
        val bridge = WorkoutTipsBridge()
        assertEquals(WorkoutTips.catalog, WorkoutAnalysis.json.decodeFromString<WorkoutTipCatalog>(bridge.catalogJson()))
        assertEquals(WorkoutTips.query("sitUp", "Technique"), WorkoutAnalysis.json.decodeFromString<List<WorkoutTip>>(bridge.queryJson("sitUp", "Technique", "")))
        assertEquals("null", bridge.featuredJson(null, "missing", 0))
    }
}
