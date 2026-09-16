package com.animesh.fitnesstracker.data.model

import androidx.room.Embedded
import androidx.room.Relation

data class GroupExerciseWithSets(
    @Embedded val groupExercise: GroupExercise,
    @Relation(parentColumn = "exerciseId", entityColumn = "id")
    val exercise: Exercise,
    @Relation(parentColumn = "id", entityColumn = "groupExerciseId")
    val sets: List<SetPrescription>
) {
    val sortedSets: List<SetPrescription> get() = sets.sortedBy { it.position }
}

data class GroupWithExercises(
    @Embedded val group: WorkoutGroup,
    @Relation(entity = GroupExercise::class, parentColumn = "id", entityColumn = "groupId")
    val exercises: List<GroupExerciseWithSets>
) {
    val sortedExercises: List<GroupExerciseWithSets> get() = exercises.sortedBy { it.groupExercise.position }
    val workingSetCount: Int get() = exercises.sumOf { e -> e.sets.count { !it.isWarmup } }
    val muscles: List<String> get() = exercises.flatMap { it.exercise.muscleList }.distinct()
}

data class RoutineSlotWithGroup(
    @Embedded val slot: RoutineSlot,
    @Relation(parentColumn = "groupId", entityColumn = "id")
    val group: WorkoutGroup?
)

data class RoutineWithSlots(
    @Embedded val routine: Routine,
    @Relation(entity = RoutineSlot::class, parentColumn = "id", entityColumn = "routineId")
    val slots: List<RoutineSlotWithGroup>
) {
    val sortedSlots: List<RoutineSlotWithGroup> get() = slots.sortedBy { it.slot.position }
}

data class SessionExerciseWithSets(
    @Embedded val exercise: SessionExercise,
    @Relation(parentColumn = "id", entityColumn = "sessionExerciseId")
    val sets: List<SessionSet>
) {
    val sortedSets: List<SessionSet> get() = sets.sortedBy { it.position }
}

data class SessionWithExercises(
    @Embedded val session: Session,
    @Relation(entity = SessionExercise::class, parentColumn = "id", entityColumn = "sessionId")
    val exercises: List<SessionExerciseWithSets>
) {
    val sortedExercises: List<SessionExerciseWithSets> get() = exercises.sortedBy { it.exercise.position }
    val completedSets: List<SessionSet> get() = exercises.flatMap { it.sets }.filter { it.completed }
    val volumeKg: Double
        get() = completedSets.filter { !it.isWarmup }.sumOf { (it.actualReps ?: 0) * (it.actualWeightKg ?: 0.0) }
}

data class MealWithDetails(
    @Embedded val meal: Meal,
    @Relation(parentColumn = "id", entityColumn = "mealId")
    val ingredients: List<Ingredient>,
    @Relation(parentColumn = "id", entityColumn = "mealId")
    val steps: List<MealStep>
) {
    val sortedIngredients: List<Ingredient> get() = ingredients.sortedBy { it.position }
    val sortedSteps: List<MealStep> get() = steps.sortedBy { it.position }
}

data class DietPlanCellWithMeal(
    @Embedded val cell: DietPlanCell,
    @Relation(parentColumn = "mealId", entityColumn = "id")
    val meal: Meal?
)

data class DietPlanWithCells(
    @Embedded val plan: DietPlan,
    @Relation(entity = DietPlanCell::class, parentColumn = "id", entityColumn = "planId")
    val cells: List<DietPlanCellWithMeal>
) {
    /** Cells for one day, in slot order; missing cells are absent (treat as empty). */
    fun day(dayOfWeek: Int): List<DietPlanCellWithMeal> = cells.filter { it.cell.dayOfWeek == dayOfWeek }.sortedBy { it.cell.slot.ordinal }
}
