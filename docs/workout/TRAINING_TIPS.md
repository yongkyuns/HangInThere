# Training tips and resource index

The offline [shared catalog](../../shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutTips.kt) contains 42 short tips,
18 linked resources and seven topics: preparation, technique, progression,
habits, recovery, nutrition and safety. Both apps use the same text, source
registry, exercise filters, search and featured-tip policy. Sources were checked
on **2026-10-31**; this is an editorial source check, not a clinician's review of
the app. Publisher dates differ from our check date.

## In the app

- **Your workouts:** one optional planning tip, with a browse button.
- **First camera setup / manual workout log:** preparation guidance.
- **Set summary / completed manual sets:** exercise-appropriate review guidance.
- Tap a tip for its explanation, source type, publisher and original link.
  Browse all tips by topic, exercise or search. Text is bundled; source links
  require a browser and internet access.

No tip interrupts a rep, changes counts or modifies the user's plan.
Featured cards are stable for the local calendar day and prefer
the current exercise when specific guidance is available. They are educational,
not observations about the user's technique, health or recovery. Safety content
is directly browsable; symptoms are never inferred from video or ratings.

## Delivery and settings

Home → **Settings** has independent, persisted switches:

- **Workout tips**, on by default, controls automatic cards and compact camera
  cues. Camera cues appear after a finished set and disappear during counting;
  they rotate using the completed-set count and the current exercise. Replay
  does not display live cues. The existing rest/readiness controls retain priority.
- **Tip notifications**, off by default, requests system permission only on
  explicit enable. Choose daily or Monday/Wednesday/Friday, with a local time
  (default 10:00). These are quiet informational notifications, independent of
  workout reminders and of whether a workout was logged that day.

Notifications use general habit/progression/recovery/nutrition tips, not symptom
warnings or a claim that the camera detected a problem. Tapping opens that
specific tip and its source links. Foreground banners stay suppressed; in-app
guidance is supplied in context. Turning notifications off cancels their pending
requests/alarms and clears delivered tip notifications. Turning either switch
off leaves the browsable library available through Settings.

The shared delivery policy handles weekdays, DST and tip choice. iOS schedules
18 upcoming tips plus at most 40 workout reminders, refilling on app activation,
calendar/time-zone and settings changes. Android keeps one inexact tip alarm,
reschedules after delivery/reboot/time changes and rejects stale/duplicate
broadcasts; battery management can delay delivery. No server or new scheduler
dependency is required. Local iOS scheduling has a finite horizon if the app is
never reopened (18 tips, about six weeks at the default frequency).

## Resource coverage

| Resource | Type and supported content | Catalog source ID |
| --- | --- | --- |
| [ACSM, 2026 resistance training guidance](https://acsm.org/resistance-training-guidelines-update-2026/) | Consensus: consistency, individualization, bodyweight training, optional failure, goal-specific load/volume | `acsm` |
| [ACSM position stand, overview of reviews](https://pmc.ncbi.nlm.nih.gov/articles/PMC12965823/) | Research: repetition duration does not establish a universal advantage for slower training | `acsm-review` |
| [Mayo Clinic, weight training technique](https://www.mayoclinic.org/healthy-lifestyle/fitness/in-depth/weight-training/art-20045842) | Clinical education: warm-up, breathing, control, reducing difficulty when form slips, trainer support | `mayo` |
| [Mayo Clinic, strength training](https://www.mayoclinic.org/healthy-lifestyle/fitness/in-depth/strength-training/art-20046670) | Clinical education: stop painful exercise; general advice applied to dips without prescribing a universal depth | `mayo-strength` |
| [CDC, adult activity recommendations](https://www.cdc.gov/physical-activity-basics/guidelines/adults.html) | Public health: strengthening frequency, all major muscle groups, aerobic activity and small amounts | `cdc-activity` |
| [NIH/MedlinePlus, exercise and fitness](https://medlineplus.gov/exerciseandphysicalfitness.html) | Public health: gradual starts and adapting activity with a provider | `nih-start` |
| [VA, exercising safely](https://www.veteranshealthlibrary.va.gov/3,82083) | Clinical education: symptoms that warrant stopping and emergency help | `va-safety` |
| [NIH/NIDDK, health tips for adults](https://www.niddk.nih.gov/health-information/weight-management/healthy-eating-physical-activity-for-life/health-tips-for-adults) | Public health: recovery day between work for the same muscle groups | `nih-recovery` |
| [ACE, chin-up progression](https://www.acefitness.org/continuing-education/certified/february-2025/8800/the-ace-do-it-better-series-chin-ups/) | Trainer practice: trunk stability, assistance, secure grip, reassessment; applies to the app's pulling exercise family | `ace-pull` |
| [ACE, push-up guide](https://www.acefitness.org/resources/everyone/exercise-library/41/push-up/) | Trainer practice: trunk and neck alignment, hand setup, coordinated press | `ace-push` |
| [ACE, strength equipment](https://www.acefitness.org/continuing-education/certified/june-2018/7010/branch-out-with-strength-equipment/) | Equipment-specific trainer practice: portable dip-bar grips/spacing; no transfer of brand-specific measurements to other bars | `ace-bars` |
| [ACE-supported rest interval study](https://www.acefitness.org/continuing-education/certified/august-2022/8121/ace-supported-research-how-long-should-you-rest-between-sets/) | Study-specific finding: trained women completed more work with longer rests; not a universal interval prescription | `ace-rest` |
| [BHF, specialist-led workout](https://www.bhf.org.uk/informationsupport/heart-matters-magazine/activity/10-minute-workout) | Exercise specialist practice: abdominal engagement and controlled sit-up return | `bhf-core` |
| [NHS, strength exercises](https://www.nhs.uk/live-well/exercise/strength-exercises/) | Public health: gradual repetition increases and wall push-up option (manual logging) | `nhs-start` |
| [Cleveland Clinic, soreness](https://health.clevelandclinic.org/should-you-workout-when-sore) | Sports physician advice: delayed soreness, lighter activity, concerning persistent/worsening pain | `cleveland-soreness` |
| [CDC, sleep](https://www.cdc.gov/heart-disease/about/sleep-and-heart-health.html) | Public health: adult sleep and regular sleep schedules | `cdc-sleep` |
| [NHS, balanced diet](https://www.nhs.uk/live-well/eat-well/how-to-eat-a-balanced-diet/eating-a-balanced-diet/) | Nutrition education: protein foods and dietary variety | `nhs-food` |
| [NHS, hydration](https://www.nhs.uk/live-well/eat-well/food-guidelines-and-food-labels/water-drinks-nutrition/) | Nutrition education: regular fluids and variable needs; no universal fluid prescription | `nhs-water` |

This is broad coverage of the current adult bodyweight-training experience,
not a claim to exhaust every publication or medical situation. The catalog
keeps practical trainer cues distinct from consensus evidence and clinical
education. Technique pages offer coaching options, not a diagnosis or a
universal definition of an acceptable counted rep.

## Editorial rules and maintenance

Use primary professional/public-health resources; summarize in original, brief
language and retain a direct link. Every tip requires a stable ID, topic,
explanation, source IDs, exercise scope and appropriate display moments.
Generic strength principles must be identified as such when applied to an
exercise. No copied images, logos or full articles are bundled.

Before adding or changing a claim, open the supporting passage, check its
population and limitations, and update the source check date. Recheck links and
recommendations before releases that change coaching content. Remove or replace
broken sources; do not substitute a publisher's homepage. No background network
fetching or new scheduled infrastructure is introduced.

The guidance targets adults and does not prescribe rehabilitation, pregnancy
adaptations, treatment, supplement doses, calorie needs or disease-specific
training. Those need separate evidence and qualified clinical input. No
“doctor reviewed” badge is used. Training for strength, muscle size and general
health should not be treated as interchangeable, and timing/rep totals alone
must not be described as measured fatigue or fitness improvement.

## Validation

Ten shared catalog/delivery/reminder tests cover filtering, opt-in, local times,
DST, relevant rest cues and the native contract. Twenty-four iOS reminder/
coaching tests and five manual-logging tests pass, including denied permission,
independent switches, scheduling races, persistence and the combined request budget.
Android compiles, including instrumentation tests; 39 unit tests and lint pass.
Shipping SwiftUI catalog renders were inspected in light/dark themes at 320/393-point
widths and with larger text. The simulator is updated; physical notification
delivery/tap-through and Android-device walkthroughs still need device checks.
No new dependency or CI job.
