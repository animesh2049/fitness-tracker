package com.animesh.fitnesstracker.garmin.fit

/**
 * Global message numbers of the Health Snapshot (HSA) file, FIT file type 128/70: the two minute
 * on-demand recording the watch offers under the Health Snapshot glance. Names and fields follow
 * the Gadgetbridge `fit_profile.json`; see research-health-snapshot.md in the project assets.
 */
internal object HsaMesg {
    const val ACCELEROMETER = 302
    const val STEP = 304
    const val SPO2 = 305
    const val STRESS = 306
    const val RESPIRATION = 307
    const val HEART_RATE = 308
    const val BODY_BATTERY = 314
    const val EVENT = 315
    const val GYROSCOPE = 376
    const val CONFIGURATION = 389
    const val WRIST_TEMPERATURE = 409
}

/**
 * Profile rows for the Health Snapshot messages, merged into [Profile.messages]. The sample
 * messages carry one array per record with `processing_interval` seconds between elements; the
 * accelerometer and gyroscope streams and the configuration blob are named here but stay raw.
 */
internal object HsaProfile {
    private const val S = "s"
    private const val MS = "ms"

    private fun f(num: Int, name: String, scale: Double = 1.0, unit: String? = null) = FieldSpec(num, name, scale, 0.0, unit)
    private fun ts() = FieldSpec(253, "timestamp", unit = S, kind = FieldKind.TIMESTAMP)
    private fun interval() = f(0, "processing_interval", unit = S)
    private fun msg(num: Int, name: String, vararg fields: FieldSpec) = MessageSpec(num, name, fields.toList())

    val messages: List<MessageSpec> = listOf(
        msg(
            HsaMesg.ACCELEROMETER, "hsa_accelerometer_data",
            f(0, "timestamp_ms", unit = MS), f(1, "sampling_interval", unit = MS), f(2, "accel_x"), f(3, "accel_y"), f(4, "accel_z"),
            f(5, "timestamp_32k", unit = "1/32768 s"), ts()
        ),
        msg(HsaMesg.STEP, "hsa_step_data", interval(), f(1, "steps", unit = "steps"), ts()),
        msg(HsaMesg.SPO2, "hsa_spo2_data", interval(), f(1, "reading_spo2", unit = "%"), f(2, "confidence"), ts()),
        msg(HsaMesg.STRESS, "hsa_stress_data", interval(), f(1, "stress_level"), ts()),
        msg(HsaMesg.RESPIRATION, "hsa_respiration_data", interval(), f(1, "respiration_rate", 100.0, unit = "brpm"), ts()),
        msg(HsaMesg.HEART_RATE, "hsa_heart_rate_data", interval(), f(1, "status"), f(2, "heart_rate", unit = "bpm"), ts()),
        msg(HsaMesg.BODY_BATTERY, "hsa_body_battery_data", interval(), f(1, "level", unit = "%"), f(2, "charged"), f(3, "uncharged"), ts()),
        msg(HsaMesg.EVENT, "hsa_event", f(0, "event_id"), ts()),
        msg(
            HsaMesg.GYROSCOPE, "hsa_gyroscope_data",
            f(0, "timestamp_ms", unit = MS), f(1, "sampling_interval", unit = "1/32768 s"), f(2, "gyro_x"), f(3, "gyro_y"), f(4, "gyro_z"),
            f(5, "timestamp_32k", unit = "1/32768 s"), ts()
        ),
        msg(HsaMesg.CONFIGURATION, "hsa_configuration_data", f(0, "data"), f(1, "data_size"), ts()),
        msg(HsaMesg.WRIST_TEMPERATURE, "hsa_wrist_temperature_data", interval(), f(1, "value", 1000.0, unit = "C"), ts())
    )
}
