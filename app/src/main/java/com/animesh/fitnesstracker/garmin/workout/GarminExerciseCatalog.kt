package com.animesh.fitnesstracker.garmin.workout

/**
 * Maps the app's exercise names onto Garmin's exercise catalogue: the FIT `exercise_category`
 * enum plus the per-category `*ExerciseName` value, as used by `workout_step` fields 10 and 11.
 * The numbers come from the FIT Java SDK (`ExerciseCategory.java`, `BenchPressExerciseName.java`
 * and friends); with a known pair the watch shows its own animation and muscle map for the step.
 *
 * Matching is by normalised name (lower case, punctuation dropped, plurals and common synonyms
 * folded), first on the whole name and then on progressively shorter suffixes, so "Smith machine
 * bench press" still lands on the bench press entry. Anything unmatched becomes a custom exercise:
 * category 65534 (UNKNOWN) with the app's exercise id as the name number, which the watch displays
 * through the accompanying `exercise_title` record.
 */
object GarminExerciseCatalog {
    /** One catalogue row: Garmin's category and name numbers plus Garmin's label for the entry. */
    data class Entry(val category: Int, val exerciseName: Int, val label: String)

    // ExerciseCategory values.
    const val BENCH_PRESS = 0
    const val CALF_RAISE = 1
    const val CARRY = 3
    const val CRUNCH = 6
    const val CURL = 7
    const val DEADLIFT = 8
    const val FLYE = 9
    const val HIP_RAISE = 10
    const val LATERAL_RAISE = 14
    const val LEG_CURL = 15
    const val LEG_RAISE = 16
    const val LUNGE = 17
    const val PLANK = 19
    const val PULL_UP = 21
    const val PUSH_UP = 22
    const val ROW = 23
    const val SHOULDER_PRESS = 24
    const val SHRUG = 26
    const val SQUAT = 28
    const val TRICEPS_EXTENSION = 30
    const val WARM_UP = 31

    /** Largest exercise_name number a custom exercise may use (65534 and 65535 are unknown and invalid). */
    private const val MAX_CUSTOM_NAME = 65533
    private const val HASH_FLOOR = 1000
    private const val HASH_RANGE = 64001

    /** Words folded to a canonical spelling before matching. */
    private val synonyms = mapOf(
        "bicep" to "biceps", "tricep" to "triceps", "dumbell" to "dumbbell", "dumbells" to "dumbbell", "dumbbells" to "dumbbell",
        "barbells" to "barbell", "flye" to "fly", "flyes" to "fly", "flies" to "fly", "flys" to "fly", "pullups" to "pullup",
        "chinups" to "chinup", "pushups" to "pushup", "situps" to "situp", "raises" to "raise", "presses" to "press",
        "crunches" to "crunch", "lunges" to "lunge", "squats" to "squat", "curls" to "curl", "rows" to "row", "dips" to "dip",
        "planks" to "plank", "thrusts" to "thrust", "bridges" to "bridge", "shrugs" to "shrug", "extensions" to "extension",
        "pushdowns" to "pushdown", "pressdowns" to "pressdown", "pulldowns" to "pulldown", "deadlifts" to "deadlift",
        "stretches" to "stretch", "swings" to "swing", "walks" to "walk", "carries" to "carry", "crushers" to "crusher",
        "ups" to "up", "kettle" to "kettlebell", "kettlebells" to "kettlebell", "hamstrings" to "hamstring", "quads" to "quad", "pecs" to "pec"
    )

    private val entries: Map<String, Entry> = buildMap {
        fun put(entry: Entry, vararg names: String) = names.forEach { this[normalise(it)] = entry }

        // Bench press (0)
        put(Entry(BENCH_PRESS, 1, "Barbell Bench Press"), "bench press", "barbell bench press", "flat bench press", "flat barbell bench press", "bb bench press")
        put(Entry(BENCH_PRESS, 6, "Dumbbell Bench Press"), "dumbbell bench press", "dumbbell press", "flat dumbbell press", "flat dumbbell bench press", "db bench press", "db press")
        put(Entry(BENCH_PRESS, 8, "Incline Barbell Bench Press"), "incline bench press", "incline barbell bench press", "incline barbell press")
        put(Entry(BENCH_PRESS, 9, "Incline Dumbbell Bench Press"), "incline dumbbell press", "incline dumbbell bench press", "incline db press")
        put(Entry(BENCH_PRESS, 2, "Close Grip Barbell Bench Press"), "close grip bench press", "close grip barbell bench press")

        // Shoulder press (24)
        put(Entry(SHOULDER_PRESS, 14, "Overhead Barbell Press"), "overhead press", "shoulder press", "barbell overhead press", "barbell shoulder press", "standing overhead press", "ohp", "standing barbell press")
        put(Entry(SHOULDER_PRESS, 25, "Military Press"), "military press")
        put(Entry(SHOULDER_PRESS, 27, "Strict Press"), "strict press")
        put(Entry(SHOULDER_PRESS, 3, "Barbell Push Press"), "push press", "barbell push press")
        put(Entry(SHOULDER_PRESS, 24, "Dumbbell Shoulder Press"), "dumbbell shoulder press", "dumbbell overhead press", "db shoulder press", "db overhead press", "standing dumbbell shoulder press")
        put(Entry(SHOULDER_PRESS, 17, "Seated Dumbbell Shoulder Press"), "seated dumbbell shoulder press", "seated dumbbell press", "seated db shoulder press")
        put(Entry(SHOULDER_PRESS, 16, "Seated Barbell Shoulder Press"), "seated barbell shoulder press", "seated overhead press", "seated shoulder press")
        put(Entry(SHOULDER_PRESS, 1, "Arnold Press"), "arnold press")
        put(Entry(SHOULDER_PRESS, 28, "Dumbbell Front Raise"), "front raise", "dumbbell front raise", "plate front raise")

        // Squat (28)
        put(Entry(SQUAT, 6, "Barbell Back Squat"), "squat", "back squat", "barbell squat", "barbell back squat", "high bar squat", "low bar squat")
        put(Entry(SQUAT, 8, "Barbell Front Squat"), "front squat", "barbell front squat")
        put(Entry(SQUAT, 0, "Leg Press"), "leg press", "machine leg press", "45 degree leg press")
        put(Entry(SQUAT, 37, "Goblet Squat"), "goblet squat", "kettlebell goblet squat", "dumbbell goblet squat")
        put(Entry(SQUAT, 29, "Dumbbell Squat"), "dumbbell squat")
        put(Entry(SQUAT, 20, "Body Weight Wall Squat"), "wall sit", "wall squat", "bodyweight wall squat")
        put(Entry(SQUAT, 21, "Weighted Wall Squat"), "weighted wall sit", "weighted wall squat")

        // Deadlift (8)
        put(Entry(DEADLIFT, 0, "Barbell Deadlift"), "deadlift", "barbell deadlift", "conventional deadlift")
        put(Entry(DEADLIFT, 23, "Romanian Deadlift"), "romanian deadlift", "rdl", "barbell romanian deadlift", "dumbbell romanian deadlift")
        put(Entry(DEADLIFT, 1, "Barbell Straight Leg Deadlift"), "stiff leg deadlift", "straight leg deadlift", "stiff legged deadlift")
        put(Entry(DEADLIFT, 15, "Sumo Deadlift"), "sumo deadlift")
        put(Entry(DEADLIFT, 17, "Trap Bar Deadlift"), "trap bar deadlift", "hex bar deadlift")

        // Lunge (17)
        put(Entry(LUNGE, 21, "Dumbbell Lunge"), "lunge", "dumbbell lunge", "reverse lunge", "forward lunge", "bodyweight lunge")
        put(Entry(LUNGE, 10, "Barbell Lunge"), "barbell lunge")
        put(Entry(LUNGE, 78, "Walking Lunge"), "walking lunge", "dumbbell walking lunge")
        put(Entry(LUNGE, 18, "Dumbbell Bulgarian Split Squat"), "bulgarian split squat", "dumbbell bulgarian split squat", "split squat", "rear foot elevated split squat")
        put(Entry(LUNGE, 7, "Barbell Bulgarian Split Squat"), "barbell bulgarian split squat", "barbell split squat")

        // Row (23)
        put(Entry(ROW, 45, "Barbell Row"), "barbell row", "bent over row", "bent over barbell row", "pendlay row", "bb row")
        put(Entry(ROW, 2, "Dumbbell Row"), "dumbbell row", "one arm dumbbell row", "single arm dumbbell row", "db row", "one arm row", "single arm row")
        put(Entry(ROW, 18, "Seated Cable Row"), "seated cable row", "cable row", "seated row", "low row", "machine row")
        put(Entry(ROW, 35, "Inverted Row"), "inverted row", "australian pull up", "bodyweight row")
        put(Entry(ROW, 28, "T-Bar Row"), "t bar row", "tbar row")
        put(Entry(ROW, 40, "Chest Supported Dumbbell Row"), "chest supported row", "chest supported dumbbell row", "seal row")
        put(Entry(ROW, 5, "Face Pull"), "face pull", "cable face pull", "rope face pull")

        // Pull-up (21)
        put(Entry(PULL_UP, 13, "Lat Pulldown"), "lat pulldown", "pulldown", "lat pull down", "cable pulldown", "machine pulldown")
        put(Entry(PULL_UP, 25, "Wide Grip Lat Pulldown"), "wide grip lat pulldown", "wide grip pulldown")
        put(Entry(PULL_UP, 38, "Pull-up"), "pull up", "pullup", "wide grip pull up", "bodyweight pull up")
        put(Entry(PULL_UP, 39, "Chin-up"), "chin up", "chinup", "underhand pull up")
        put(Entry(PULL_UP, 24, "Weighted Pull-up"), "weighted pull up", "weighted pullup")
        put(Entry(PULL_UP, 41, "Weighted Chin-up"), "weighted chin up", "weighted chinup")

        // Curl (7)
        put(Entry(CURL, 46, "Dumbbell Biceps Curl"), "biceps curl", "bicep curl", "curl", "dumbbell curl", "dumbbell biceps curl", "db curl", "standing dumbbell curl")
        put(Entry(CURL, 3, "Barbell Biceps Curl"), "barbell curl", "barbell biceps curl", "bb curl", "ez bar curl")
        put(Entry(CURL, 16, "Dumbbell Hammer Curl"), "hammer curl", "dumbbell hammer curl", "db hammer curl")
        put(Entry(CURL, 9, "Cable Hammer Curl"), "cable hammer curl", "rope hammer curl")
        put(Entry(CURL, 19, "EZ Bar Preacher Curl"), "preacher curl", "ez bar preacher curl", "machine preacher curl")
        put(Entry(CURL, 0, "Alternating Dumbbell Biceps Curl"), "alternating dumbbell curl", "alternating curl")

        // Triceps extension (30)
        put(Entry(TRICEPS_EXTENSION, 39, "Triceps Pressdown"), "triceps pushdown", "tricep pushdown", "pushdown", "cable pushdown", "triceps pressdown", "tricep pressdown", "cable triceps pushdown", "bar pushdown")
        put(Entry(TRICEPS_EXTENSION, 19, "Rope Pressdown"), "rope pushdown", "rope pressdown", "rope triceps pushdown")
        put(Entry(TRICEPS_EXTENSION, 13, "Lying EZ Bar Triceps Extension"), "skull crusher", "skullcrusher", "lying triceps extension", "ez bar skull crusher", "barbell skull crusher", "lying barbell triceps extension")
        put(Entry(TRICEPS_EXTENSION, 7, "Dumbbell Lying Triceps Extension"), "dumbbell skull crusher", "lying dumbbell triceps extension")
        put(Entry(TRICEPS_EXTENSION, 5, "Cable Overhead Triceps Extension"), "overhead triceps extension", "cable overhead triceps extension", "overhead cable extension")
        put(Entry(TRICEPS_EXTENSION, 2, "Body Weight Dip"), "dip", "bodyweight dip", "triceps dip", "chest dip", "parallel bar dip", "bar dip")
        put(Entry(TRICEPS_EXTENSION, 40, "Weighted Dip"), "weighted dip")
        put(Entry(TRICEPS_EXTENSION, 0, "Bench Dip"), "bench dip")

        // Lateral raise (14)
        put(Entry(LATERAL_RAISE, 34, "Dumbbell Lateral Raise"), "lateral raise", "side lateral raise", "dumbbell lateral raise", "side raise", "db lateral raise", "cable lateral raise")
        put(Entry(LATERAL_RAISE, 24, "Seated Lateral Raise"), "seated lateral raise")

        // Flye (9)
        put(Entry(FLYE, 2, "Dumbbell Flye"), "chest fly", "fly", "dumbbell fly", "pec fly", "flat dumbbell fly", "dumbbell chest fly", "machine fly", "pec deck")
        put(Entry(FLYE, 0, "Cable Crossover"), "cable fly", "cable crossover", "cable chest fly", "standing cable fly")
        put(Entry(FLYE, 3, "Incline Dumbbell Flye"), "incline dumbbell fly", "incline fly")
        put(Entry(FLYE, 11, "Incline Reverse Flye"), "rear delt fly", "reverse fly", "rear delt raise", "bent over reverse fly", "incline reverse fly")
        put(Entry(FLYE, 6, "Single Arm Standing Cable Reverse Flye"), "cable rear delt fly", "cable reverse fly")

        // Plank (19)
        put(Entry(PLANK, 43, "Plank"), "plank", "front plank", "forearm plank", "elbow plank")
        put(Entry(PLANK, 66, "Side Plank"), "side plank")
        put(Entry(PLANK, 117, "Weighted Plank"), "weighted plank")

        // Calf raise (1)
        put(Entry(CALF_RAISE, 18, "Standing Calf Raise"), "calf raise", "standing calf raise", "machine calf raise")
        put(Entry(CALF_RAISE, 17, "Standing Barbell Calf Raise"), "barbell calf raise", "standing barbell calf raise")
        put(Entry(CALF_RAISE, 6, "Seated Calf Raise"), "seated calf raise")

        // Hip raise (10)
        put(Entry(HIP_RAISE, 1, "Barbell Hip Thrust with Bench"), "hip thrust", "barbell hip thrust", "hip thrust with bench")
        put(Entry(HIP_RAISE, 0, "Barbell Hip Thrust on Floor"), "hip thrust on floor", "floor hip thrust")
        put(Entry(HIP_RAISE, 11, "Hip Raise"), "glute bridge", "hip bridge", "bridge", "hip raise", "bodyweight glute bridge")
        put(Entry(HIP_RAISE, 23, "Kettlebell Swing"), "kettlebell swing", "kb swing")

        // Push-up (22)
        put(Entry(PUSH_UP, 77, "Push-up"), "push up", "pushup", "press up", "bodyweight push up")

        // Crunch (6)
        put(Entry(CRUNCH, 83, "Crunch"), "crunch", "ab crunch", "floor crunch")
        put(Entry(CRUNCH, 1, "Cable Crunch"), "cable crunch", "rope crunch")
        put(Entry(CRUNCH, 28, "Kneeling Cable Crunch"), "kneeling cable crunch")
        put(Entry(CRUNCH, 0, "Bicycle Crunch"), "bicycle crunch")
        put(Entry(CRUNCH, 79, "Weighted Crunch"), "weighted crunch")

        // Leg raise (16)
        put(Entry(LEG_RAISE, 1, "Hanging Leg Raise"), "hanging leg raise", "hanging straight leg raise")
        put(Entry(LEG_RAISE, 0, "Hanging Knee Raise"), "hanging knee raise", "knee raise")
        put(Entry(LEG_RAISE, 2, "Weighted Hanging Leg Raise"), "weighted hanging leg raise")

        // Leg curl (15)
        put(Entry(LEG_CURL, 0, "Leg Curl"), "leg curl", "lying leg curl", "seated leg curl", "hamstring curl", "machine leg curl")
        put(Entry(LEG_CURL, 2, "Good Morning"), "good morning", "barbell good morning")

        // Shrug (26), carry (3)
        put(Entry(SHRUG, 1, "Barbell Shrug"), "shrug", "barbell shrug")
        put(Entry(SHRUG, 5, "Dumbbell Shrug"), "dumbbell shrug")
        put(Entry(CARRY, 1, "Farmers Walk"), "farmers walk", "farmer walk", "farmers carry", "farmer carry")

        // Warm-up and stretches (31)
        put(Entry(WARM_UP, 44, "Stretch Hamstring"), "hamstring stretch", "seated hamstring stretch", "lying hamstring stretch")
        put(Entry(WARM_UP, 33, "Standing Hamstring Stretch"), "standing hamstring stretch")
        put(Entry(WARM_UP, 45, "Stretch Hip Flexor and Quad"), "couch stretch", "hip flexor and quad stretch")
        put(Entry(WARM_UP, 49, "Stretch Lunging Hip Flexor"), "hip flexor stretch", "lunging hip flexor stretch", "kneeling hip flexor stretch")
        put(Entry(WARM_UP, 61, "Stretch Quad"), "quad stretch", "standing quad stretch")
        put(Entry(WARM_UP, 39, "Stretch Childs Pose"), "childs pose", "child pose")
        put(Entry(WARM_UP, 37, "Stretch Calf"), "calf stretch")
        put(Entry(WARM_UP, 38, "Stretch Cat Cow"), "cat cow", "cat camel")
        put(Entry(WARM_UP, 59, "Stretch Pigeon Pose"), "pigeon pose", "pigeon stretch")
        put(Entry(WARM_UP, 58, "Stretch Pectoral"), "chest stretch", "pec stretch", "pectoral stretch")
        put(Entry(WARM_UP, 46, "Stretch Lat"), "lat stretch")
        put(Entry(WARM_UP, 63, "Stretch Shoulder"), "shoulder stretch")
        put(Entry(WARM_UP, 69, "Stretch Triceps"), "triceps stretch", "tricep stretch")
        put(Entry(WARM_UP, 32, "Glutes Stretch"), "glute stretch", "glutes stretch")
    }

    /** How many catalogue entries (distinct normalised names) the table holds. */
    val size: Int get() = entries.size

    /** Garmin's entry for an app exercise name, or null when the name is not in the table. */
    fun lookup(name: String): Entry? {
        val tokens = normalise(name).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        for (drop in 0 until tokens.size) {
            entries[tokens.drop(drop).joinToString(" ")]?.let { return it }
        }
        return null
    }

    /**
     * The mapping for one app exercise. Known names get Garmin's (category, exercise_name); unknown
     * ones become custom: category 65534 and the app exercise id as the name number (a stable hash
     * in 1000..65000 when the id does not fit the u16 range), with no catalogue label.
     */
    fun map(appExerciseId: Long, name: String): ExerciseMapping {
        val entry = lookup(name)
        return if (entry != null) {
            ExerciseMapping(appExerciseId, name, entry.category, entry.exerciseName, entry.label)
        } else {
            ExerciseMapping(appExerciseId, name, ExerciseMapping.CUSTOM_CATEGORY, customNameNumber(appExerciseId), null)
        }
    }

    /** The exercise_name number for a custom exercise: the id itself when it fits, else a stable hash in 1000..65000. */
    fun customNameNumber(appExerciseId: Long): Int {
        if (appExerciseId in 0..MAX_CUSTOM_NAME.toLong()) return appExerciseId.toInt()
        val hash = (appExerciseId xor (appExerciseId ushr 32)).toInt()
        return HASH_FLOOR + Math.floorMod(hash, HASH_RANGE)
    }

    /**
     * Lower case, apostrophes removed, other punctuation turned into spaces, synonyms folded,
     * whitespace collapsed. "Child's pose" becomes "childs pose", "Pull-ups" becomes "pullup".
     */
    fun normalise(name: String): String {
        val lowered = name.lowercase().replace("'", "").replace("’", "")
        val spaced = lowered.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        return spaced.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { synonyms[it] ?: it }
    }
}
