package com.animesh.workouttracker

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.animesh.workouttracker.data.AppDatabase
import com.animesh.workouttracker.data.Seed
import com.animesh.workouttracker.data.model.SessionStatus
import com.animesh.workouttracker.repository.GroupRepository
import com.animesh.workouttracker.repository.PlannedExercise
import com.animesh.workouttracker.repository.PlannedSet
import com.animesh.workouttracker.repository.SessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: AppDatabase
    private lateinit var sessions: SessionRepository
    private lateinit var groups: GroupRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        sessions = SessionRepository(db)
        groups = GroupRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun seedCreatesLibraryAndSampleRoutine() = runTest {
        Seed.runIfNeeded(db)
        assertTrue(db.exerciseDao().count() >= 35)
        val routine = db.routineDao().getActive()
        assertNotNull(routine)
        assertEquals(4, routine!!.slots.size)
        assertNull(routine.sortedSlots[3].slot.groupId)
        val push = db.groupDao().getGroup(routine.sortedSlots[0].slot.groupId!!)!!
        assertEquals("Push day", push.group.name)
        assertEquals(5, push.exercises.size)
        assertEquals(15, push.workingSetCount)
        // Seeding twice is a no-op.
        Seed.runIfNeeded(db)
        assertEquals(1, db.routineDao().getAllRoutines().size)
    }

    @Test
    fun inProgressSessionIsRecoverableAndSetsAreQueriedByExercise() = runTest {
        Seed.runIfNeeded(db)
        val bench = db.exerciseDao().getAll().first { it.name == "Bench press" }
        val plan = listOf(
            PlannedExercise(bench, null, false, 90, listOf(PlannedSet(8, 60.0), PlannedSet(8, 60.0), PlannedSet(8, 60.0)))
        )
        val id = sessions.start(null, "Push day", null, plan)
        val inProgress = sessions.getInProgress()
        assertNotNull(inProgress)
        assertEquals(id, inProgress!!.session.id)
        assertEquals(3, inProgress.exercises[0].sets.size)

        val sets = inProgress.exercises[0].sortedSets
        sessions.completeSet(sets[0], 8, 60.0, null)
        sessions.completeSet(sets[1], 8, 60.0, null)
        sessions.completeSet(sets[2], 7, 60.0, null)
        // Not visible to history queries until completed.
        assertEquals(0, sessions.setsForExercise(bench.id).size)
        sessions.finish(id, "", writeBackTargets = false)
        assertNull(sessions.getInProgress())
        val dated = sessions.setsForExercise(bench.id)
        assertEquals(3, dated.size)
        assertEquals(7, dated.last().actualReps)
        assertEquals(SessionStatus.COMPLETED, sessions.getSession(id)!!.session.status)
    }

    @Test
    fun finishWritesAcceptedTargetsBackToPrescriptions() = runTest {
        Seed.runIfNeeded(db)
        val routine = db.routineDao().getActive()!!
        val push = db.groupDao().getGroup(routine.sortedSlots[0].slot.groupId!!)!!
        val benchGe = push.sortedExercises[0]
        assertEquals(60.0, benchGe.sortedSets[1].targetWeightKg)
        val plan = listOf(
            PlannedExercise(
                benchGe.exercise, benchGe.groupExercise.id, false, 90,
                listOf(PlannedSet(8, 40.0, isWarmup = true), PlannedSet(8, 62.5), PlannedSet(8, 62.5), PlannedSet(8, 62.5))
            )
        )
        val id = sessions.start(push.group.id, "Push day", routine.routine.id, plan)
        sessions.finish(id, "", writeBackTargets = true)
        val after = db.groupDao().getGroup(push.group.id)!!.sortedExercises[0].sortedSets
        assertEquals(40.0, after[0].targetWeightKg) // warm-up untouched
        assertEquals(62.5, after[1].targetWeightKg)
        assertEquals(62.5, after[3].targetWeightKg)
    }

    @Test
    fun groupRepositoryAddsAndRemovesSets() = runTest {
        Seed.runIfNeeded(db)
        val bench = db.exerciseDao().getAll().first { it.name == "Bench press" }
        val gid = groups.createGroup("Test")
        val geId = groups.addExercise(gid, bench)
        var g = groups.observeGroup(gid).first()!!
        assertEquals(3, g.exercises[0].sets.size)
        groups.addSet(geId, bench)
        g = groups.observeGroup(gid).first()!!
        assertEquals(4, g.exercises[0].sets.size)
        groups.removeSet(g.exercises[0].sortedSets[0])
        g = groups.observeGroup(gid).first()!!
        assertEquals(listOf(0, 1, 2), g.exercises[0].sortedSets.map { it.position })
    }
}
