package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class WorkoutTipSource(
    val id: String, val publisher: String, val title: String, val url: String, val kind: String,
    val checkedOn: String = "2026-10-31",
)
@Serializable data class WorkoutTip(
    val id: String, val category: String, val title: String, val cue: String, val detail: String,
    val sourceIds: List<String>, val exercises: List<String> = emptyList(), val moments: List<String> = emptyList(),
)
@Serializable data class WorkoutTipCatalog(
    val version: Int = 1, val audience: String = "General guidance for adults. Adapt your training with a qualified professional if you have an injury or health condition.",
    val sources: List<WorkoutTipSource>, val tips: List<WorkoutTip>,
)

/** One offline, editorially curated catalog for both apps. No medical or pose-based diagnosis. */
object WorkoutTips {
    private fun source(id: String, publisher: String, title: String, url: String, kind: String) =
        WorkoutTipSource(id, publisher, title, url, kind)
    private fun tip(id: String, category: String, title: String, cue: String, detail: String, source: String,
        exercise: String? = null, moments: List<String> = emptyList()) =
        WorkoutTip(id, category, title, cue, detail, listOf(source), exercise?.let(::listOf) ?: emptyList(), moments)
    val catalog = WorkoutTipCatalog(sources = listOf(
        source("acsm", "ACSM", "2026 resistance training guidance", "https://acsm.org/resistance-training-guidelines-update-2026/", "Consensus guidance"),
        source("acsm-review", "ACSM · Medicine & Science in Sports & Exercise", "Resistance training prescription: overview of reviews", "https://pmc.ncbi.nlm.nih.gov/articles/PMC12965823/", "Research review"),
        source("mayo", "Mayo Clinic", "Weight training: proper technique", "https://www.mayoclinic.org/healthy-lifestyle/fitness/in-depth/weight-training/art-20045842", "Clinical guidance"),
        source("mayo-strength", "Mayo Clinic", "Strength training: get stronger, leaner, healthier", "https://www.mayoclinic.org/healthy-lifestyle/fitness/in-depth/strength-training/art-20046670", "Clinical guidance"),
        source("cdc-activity", "CDC", "Adult physical activity recommendations", "https://www.cdc.gov/physical-activity-basics/guidelines/adults.html", "Public health guidance"),
        source("nih-start", "NIH · MedlinePlus", "Exercise and physical fitness", "https://medlineplus.gov/exerciseandphysicalfitness.html", "Public health guidance"),
        source("va-safety", "US Department of Veterans Affairs", "Exercising safely", "https://www.veteranshealthlibrary.va.gov/3,82083", "Clinical guidance"),
        source("nih-recovery", "NIH · NIDDK", "Health tips for adults", "https://www.niddk.nih.gov/health-information/weight-management/healthy-eating-physical-activity-for-life/health-tips-for-adults", "Public health guidance"),
        source("ace-pull", "American Council on Exercise", "The Do It Better Series: chin-ups", "https://www.acefitness.org/continuing-education/certified/february-2025/8800/the-ace-do-it-better-series-chin-ups/", "Trainer guidance"),
        source("ace-push", "American Council on Exercise", "Push-up exercise guide", "https://www.acefitness.org/resources/everyone/exercise-library/41/push-up/", "Trainer guidance"),
        source("ace-bars", "American Council on Exercise", "Branch out with strength equipment", "https://www.acefitness.org/continuing-education/certified/june-2018/7010/branch-out-with-strength-equipment/", "Trainer guidance"),
        source("ace-rest", "American Council on Exercise", "How long should you rest between sets?", "https://www.acefitness.org/continuing-education/certified/august-2022/8121/ace-supported-research-how-long-should-you-rest-between-sets/", "ACE-supported research"),
        source("bhf-core", "British Heart Foundation", "10-minute workout with a physical activity specialist", "https://www.bhf.org.uk/informationsupport/heart-matters-magazine/activity/10-minute-workout", "Exercise specialist guidance"),
        source("nhs-start", "NHS", "Strength exercises", "https://www.nhs.uk/live-well/exercise/strength-exercises/", "Public health guidance"),
        source("cleveland-soreness", "Cleveland Clinic", "Is it normal to be sore after a workout?", "https://health.clevelandclinic.org/should-you-workout-when-sore", "Sports physician guidance"),
        source("cdc-sleep", "CDC", "Sleep and heart health", "https://www.cdc.gov/heart-disease/about/sleep-and-heart-health.html", "Public health guidance"),
        source("nhs-food", "NHS", "Eating a balanced diet", "https://www.nhs.uk/live-well/eat-well/how-to-eat-a-balanced-diet/eating-a-balanced-diet/", "Nutrition guidance"),
        source("nhs-water", "NHS", "Water, drinks and hydration", "https://www.nhs.uk/live-well/eat-well/food-guidelines-and-food-labels/water-drinks-nutrition/", "Nutrition guidance"),
    ), tips = listOf(
        tip("warm-up", "Preparation", "Start warm", "Take 5–10 minutes to warm up.", "Easy walking or similar movement is a useful start before strength work.", "mayo", moments = listOf("prepare")),
        tip("breathe", "Technique", "Keep breathing", "Breathe out through the effort.", "For pulling and pressing, exhale as you lift yourself; inhale on the return. Avoid holding your breath.", "mayo", moments = listOf("prepare")),
        tip("control", "Technique", "Stay in control", "Use smooth movement rather than momentum.", "Choose a pace and difficulty that let you control the whole movement.", "mayo", moments = listOf("prepare", "review")),
        tip("form-first", "Progression", "Finish with control", "Reduce reps or difficulty when technique slips.", "A smaller, controlled set is a useful choice when you cannot maintain your technique.", "mayo", moments = listOf("review")),
        tip("ask-trainer", "Safety", "Get another pair of eyes", "Unsure about technique? Ask a qualified trainer.", "An in-person check can help you learn a movement and choose a suitable difficulty.", "mayo"),
        tip("pain", "Safety", "Pain is a reason to stop", "Stop an exercise that causes pain.", "Do not push through joint pain. If it persists or returns, seek advice from a healthcare professional.", "mayo-strength"),
        tip("start-small", "Preparation", "Start where you are", "Begin with a manageable amount.", "Build activity gradually, especially when returning after time away.", "nih-start", moments = listOf("prepare", "plan")),
        tip("small-increase", "Progression", "Build gradually", "Increase repetitions gradually as exercise gets easier.", "Start with an amount you can manage rather than making a large jump.", "nhs-start", moments = listOf("review", "plan")),
        tip("simple-plan", "Habits", "Simple can work", "Choose a routine you can keep doing.", "Regular resistance training matters more than a complicated plan for most healthy adults.", "acsm", moments = listOf("plan")),
        tip("bodyweight", "Preparation", "Your body is resistance", "Bodyweight training can build strength.", "Bands and home routines can also be effective; a gym is not essential.", "acsm", moments = listOf("plan")),
        tip("failure", "Progression", "Failure is optional", "You do not need to reach failure every set.", "Training to momentary muscle failure is not required for general strength and muscle gains.", "acsm", moments = listOf("review")),
        tip("personal-plan", "Habits", "Make the plan fit you", "Choose training that fits your goals and schedule.", "Enjoyment and a manageable routine support sticking with resistance training.", "acsm", moments = listOf("plan")),
        tip("goal-specific", "Progression", "Train for your goal", "Strength and muscle growth need different emphasis.", "Load and weekly training volume can be adjusted to your goal with a qualified trainer.", "acsm", moments = listOf("plan")),
        tip("tempo", "Progression", "Slower is not automatically better", "Use tempo to practice control, not chase a score.", "Research does not show that ever-slower repetitions automatically produce more muscle growth.", "acsm-review", moments = listOf("review")),
        tip("strength-days", "Habits", "Make room for strength", "Aim for strength work on at least two days a week.", "Adult guidelines include all major muscle groups. These four app exercises alone do not cover the whole recommendation.", "cdc-activity", moments = listOf("plan")),
        tip("whole-body", "Habits", "Remember your legs", "Train your lower body as well as your upper body.", "Adult strengthening guidance includes legs and hips alongside back, abdomen, chest, shoulders and arms.", "cdc-activity", moments = listOf("plan")),
        tip("aerobic", "Habits", "Strength is one part", "Make room for walking or other aerobic activity too.", "Adult guidance also includes 150 minutes of moderate aerobic activity weekly, or 75 vigorous minutes, or an equivalent mix.", "cdc-activity", moments = listOf("plan")),
        tip("short-session", "Habits", "A short session counts", "Some activity is better than none.", "You can build toward weekly activity recommendations in smaller chunks.", "cdc-activity", moments = listOf("plan")),
        tip("recovery-day", "Recovery", "Give muscles a recovery day", "Leave a day before training the same muscles again.", "This is a simple starting guideline; an individual training plan may distribute muscle groups differently.", "nih-recovery", moments = listOf("review", "plan")),
        tip("rest-between", "Recovery", "Give the next set a chance", "Longer rests can help you maintain repetitions.", "Rest needs depend on load and goal. An ACE-supported study in trained women found more work completed with three-minute rests than one-minute rests.", "ace-rest", moments = listOf("review", "plan")),
        tip("soreness", "Recovery", "Soreness can arrive later", "New exercise can leave you sore the next day.", "Mild delayed soreness is common. Pain that worsens, persists or limits normal activities deserves professional advice.", "cleveland-soreness", moments = listOf("review")),
        tip("sore-day", "Recovery", "Adjust a sore day", "Choose lighter activity if you are mildly sore.", "Avoid strenuous work when soreness is more painful or makes your usual movements difficult.", "cleveland-soreness", moments = listOf("plan")),
        tip("sleep", "Recovery", "Make space for sleep", "Most adults need at least seven hours a night.", "Regular sleep supports the body's repair processes. A consistent bedtime and wake time can help.", "cdc-sleep", moments = listOf("review", "plan")),
        tip("food", "Nutrition", "Food supports recovery", "Include protein foods in a balanced diet.", "Beans, lentils, eggs, fish and other protein foods help the body grow and repair itself.", "nhs-food", moments = listOf("review")),
        tip("water", "Nutrition", "Keep water handy", "Drink regularly through the day.", "Physical activity and hot conditions can increase fluid needs. Follow any fluid restriction prescribed by your clinician.", "nhs-water", moments = listOf("prepare", "review")),
        tip("food-balance", "Nutrition", "Think beyond protein", "A varied diet matters too.", "Include starchy foods, fruit, vegetables and other food groups rather than focusing on one nutrient.", "nhs-food", moments = listOf("plan")),
        tip("pull-brace", "Technique", "Quiet the swing", "Brace your midsection while pulling.", "A stable trunk helps reduce reliance on swinging your legs for momentum.", "ace-pull", "pullUp", listOf("prepare", "review")),
        tip("pull-assisted", "Progression", "Assistance is practice", "Use a suitable assisted variation while building strength.", "A trainer can help set up band assistance or a secure low bar with feet supported.", "ace-pull", "pullUp", listOf("review", "plan")),
        tip("pull-grip", "Safety", "Respect your grip", "Stop before your grip becomes insecure.", "Supported practice can help build grip strength while keeping your feet available for support.", "ace-pull", "pullUp"),
        tip("pull-repeat", "Progression", "Practice, then reassess", "Revisit a consistent pulling variation over time.", "Periodic reassessment can show progress as you build toward an unassisted chin-up.", "ace-pull", "pullUp", listOf("review")),
        tip("push-line", "Technique", "Move as one", "Keep your trunk steady as you lower and press.", "Avoid letting your hips sag or rise ahead of your shoulders.", "ace-push", "pushUp", listOf("prepare", "review")),
        tip("push-head", "Technique", "Keep your neck in line", "Let your head follow your spine.", "Keep the same body alignment through the lowering and pressing phases.", "ace-push", "pushUp", listOf("prepare")),
        tip("push-hands", "Technique", "Set your hands", "Start around shoulder width and adjust comfortably.", "Set your palms securely and brace your torso before beginning the push-up.", "ace-push", "pushUp", listOf("prepare")),
        tip("push-floor", "Technique", "Push the floor away", "Press up while keeping your body steady.", "Use the same controlled trunk position on the way up and down.", "ace-push", "pushUp", listOf("review")),
        tip("push-wall", "Progression", "Start at the wall", "Wall push-ups are an option for building up gradually.", "Keep your back straight and move slowly. In this app, log wall push-ups manually rather than relying on the floor-exercise detector.", "nhs-start", "pushUp", listOf("plan")),
        tip("dip-bars", "Preparation", "Set up the bars", "Use secure grips and suitable bar spacing.", "For portable dip bars, follow the equipment instructions. ACE's portable-bar guidance emphasizes grip placement and spacing.", "ace-bars", "dip", listOf("prepare")),
        tip("dip-control", "Technique", "Own the lowering", "Lower with control rather than dropping.", "The general strength-training principle of avoiding rushed movement applies to dips too.", "mayo", "dip", listOf("prepare", "review")),
        tip("dip-pain", "Safety", "Do not force dip depth", "Stop if the movement causes pain.", "Choose a tolerable alternative with a qualified professional if dips repeatedly hurt your shoulders.", "mayo-strength", "dip"),
        tip("sit-brace", "Technique", "Start with your middle", "Engage your abdominal muscles as you curl up.", "Bring your chest toward your knees with a controlled movement.", "bhf-core", "sitUp", listOf("prepare", "review")),
        tip("sit-return", "Technique", "Control the return", "Lower yourself smoothly back to the start.", "Keep your abdominal muscles engaged rather than dropping back to the floor.", "bhf-core", "sitUp", listOf("prepare", "review")),
        tip("health-check", "Safety", "Adapt to your health", "Ask for guidance if you are unsure exercise is safe for you.", "A healthcare professional can help adapt activity for health conditions or difficulties with movement and balance.", "nih-start"),
        tip("symptoms", "Safety", "Know when to seek help", "Stop exercising for chest pain, dizziness or severe weakness.", "VA guidance advises calling emergency services for these symptoms. Do not resume your workout to test whether they go away.", "va-safety"),
    ))
    val categories: List<String> = catalog.tips.map { it.category }.distinct()
    private fun exerciseKey(name: String?) = name?.let(WorkoutActivityProgress::exerciseKey)
    fun query(exercise: String? = null, category: String? = null, search: String = ""): List<WorkoutTip> {
        val key = exerciseKey(exercise)
        val terms = search.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return catalog.tips.filter { tip ->
            (key == null || tip.exercises.isEmpty() || tip.exercises.any { exerciseKey(it) == key }) &&
                (category == null || tip.category == category) &&
                terms.all { term -> listOf(tip.title, tip.cue, tip.detail, tip.category).any { term in it.lowercase() } }
        }
    }
    /** Stable within a day; reading a tip never changes workout state or goals. */
    fun featured(exercise: String?, moment: String, day: Long): WorkoutTip? {
        val eligible = query(exercise).filter { moment in it.moments }
        val specific = eligible.filter { it.exercises.isNotEmpty() }
        val pool = specific.ifEmpty { eligible }
        return pool.takeIf { it.isNotEmpty() }?.let { it[((day % it.size + it.size) % it.size).toInt()] }
    }
}

class WorkoutTipsBridge {
    fun catalogJson(): String = WorkoutAnalysis.json.encodeToString(WorkoutTips.catalog)
    fun queryJson(exercise: String?, category: String?, search: String): String =
        WorkoutAnalysis.json.encodeToString(WorkoutTips.query(exercise, category, search))
    fun featuredJson(exercise: String?, moment: String, day: Long): String =
        WorkoutAnalysis.json.encodeToString(WorkoutTips.featured(exercise, moment, day))
}
