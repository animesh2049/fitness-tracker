package com.animesh.fitnesstracker.garmin.fit

import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.ENUM
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.UINT16
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.UINT32
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.UINT8
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.le16
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.le32
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.u8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The profile rows added for workouts: message 225 (set) from an activity file, and 26/27/264 field metadata. */
class WorkoutMessagesDecodeTest {
    private val garminTs = 1_150_000_000L
    private val unixTs = garminTs + GARMIN_EPOCH_UNIX_SECONDS

    @Test
    fun setMessageDecodesFromASyntheticActivityRecord() {
        val b = FitFileBuilder()
        b.definition(0, Mesg.FILE_ID, listOf(Slot(0, 1, ENUM), Slot(4, 4, UINT32))).data(0, u8(4), le32(garminTs))
        b.definition(
            1, Mesg.SET,
            listOf(
                Slot(254, 4, UINT32), Slot(0, 4, UINT32), Slot(3, 2, UINT16), Slot(4, 2, UINT16), Slot(5, 1, UINT8), Slot(6, 4, UINT32),
                Slot(7, 4, UINT16), Slot(8, 4, UINT16), Slot(9, 2, UINT16), Slot(10, 2, UINT16), Slot(11, 2, UINT16)
            )
        )
        b.data(
            1, le32(garminTs + 45), le32(45_000), le16(8), le16(60 * 16), u8(1), le32(garminTs),
            le16(0) + le16(0xFFFF), le16(1) + le16(0xFFFF), le16(1), le16(3), le16(2)
        )
        b.data(
            1, le32(garminTs + 165), le32(120_000), le16(0xFFFF), le16(0xFFFF), u8(0), le32(garminTs + 45),
            le16(0xFFFF) + le16(0xFFFF), le16(0xFFFF) + le16(0xFFFF), le16(0xFFFF), le16(4), le16(0xFFFF)
        )
        val d = FitDecoder.decode(b.build())
        assertEquals(FitFileType.ACTIVITY, d.fileId.type)
        assertEquals(0, d.unknownFieldCount)
        val active = d.sets[0]
        assertEquals(unixTs + 45, active.timestamp)
        assertEquals(45.0, active.durationSeconds!!, 1e-9)
        assertEquals(8, active.repetitions)
        assertEquals(60.0, active.weightKg!!, 1e-9)
        assertEquals(1, active.setType)
        assertEquals(unixTs, active.startTime)
        assertEquals(listOf(0, 0), active.category)
        assertEquals(listOf(1, 0), active.categorySubtype)
        assertEquals(1, active.weightDisplayUnit)
        assertEquals(3, active.messageIndex)
        assertEquals(2, active.wktStepIndex)
        val rest = d.sets[1]
        assertEquals(0, rest.setType)
        assertNull(rest.repetitions)
        assertNull(rest.weightKg)
        assertNull(rest.wktStepIndex)
        assertEquals(emptyList<Int>(), rest.category)
        assertEquals(120.0, rest.durationSeconds!!, 1e-9)
    }

    @Test
    fun setTimestampFallsBackToField253() {
        val w = FitWriter()
        w.write(Mesg.FILE_ID, FitField.enum(0, 4), FitField.u32(4, garminTs))
        w.write(Mesg.SET, FitField.u32(253, garminTs + 7), FitField.u16(3, 12))
        val set = FitDecoder.decode(w.toByteArray()).sets.single()
        assertEquals(unixTs + 7, set.timestamp)
        assertEquals(12, set.repetitions)
    }

    @Test
    fun workoutMessagesHaveTheDocumentedScalesAndNames() {
        assertEquals("workout", Profile.message(Mesg.WORKOUT)!!.name)
        assertEquals("wkt_name", Profile.field(Mesg.WORKOUT, 8)!!.name)
        assertEquals(FieldKind.STRING, Profile.field(Mesg.WORKOUT, 8)!!.kind)
        assertEquals("num_valid_steps", Profile.field(Mesg.WORKOUT, 6)!!.name)
        assertEquals("workout_step", Profile.message(Mesg.WORKOUT_STEP)!!.name)
        assertEquals(100.0, Profile.field(Mesg.WORKOUT_STEP, 12)!!.scale, 0.0)
        assertEquals("kg", Profile.field(Mesg.WORKOUT_STEP, 12)!!.unit)
        assertEquals("duration_type", Profile.field(Mesg.WORKOUT_STEP, 1)!!.name)
        assertEquals("exercise_title", Profile.message(Mesg.EXERCISE_TITLE)!!.name)
        assertEquals("wkt_step_name", Profile.field(Mesg.EXERCISE_TITLE, 2)!!.name)
        assertEquals("set", Profile.message(Mesg.SET)!!.name)
        assertEquals(16.0, Profile.field(Mesg.SET, 4)!!.scale, 0.0)
        assertEquals(1000.0, Profile.field(Mesg.SET, 0)!!.scale, 0.0)
        assertEquals(FieldKind.TIMESTAMP, Profile.field(Mesg.SET, 254)!!.kind)
        assertEquals(FieldKind.TIMESTAMP, Profile.field(Mesg.SET, 6)!!.kind)
        assertEquals("wkt_step_index", Profile.field(Mesg.SET, 11)!!.name)
        assertNotNull(Profile.field(Mesg.SET, 10))
    }

    @Test
    fun workoutRecordKeepsItsOldShapeAndGainsTheNewFields() {
        val legacy = WorkoutRec("Legs", 10)
        assertNull(legacy.subSport)
        assertNull(legacy.numValidSteps)
        val w = FitWriter()
        w.write(Mesg.FILE_ID, FitField.enum(0, 5), FitField.u32(4, garminTs))
        w.write(Mesg.WORKOUT, FitField.enum(4, 10), FitField.enum(11, 20), FitField.u16(6, 7), FitField.u32z(5, 32L), FitField.string(8, "Legs"))
        val rec = FitDecoder.decode(w.toByteArray()).workouts.single()
        assertEquals(WorkoutRec("Legs", 10, 20, 7, 32L, null), rec)
    }
}
